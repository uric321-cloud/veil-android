"""SAM.gov Opportunities API client with a daily request budget.

Official API: https://open.gsa.gov/api/get-opportunities-public-api/
  GET https://api.sam.gov/opportunities/v2/search?api_key=...&postedFrom=MM/dd/yyyy&postedTo=MM/dd/yyyy&limit=..&offset=..&ptype=o,k
  GET https://api.sam.gov/prod/opportunities/v1/noticedesc?noticeid=...&api_key=...

Every call counts against the key's daily quota, so every call goes through
`_spend()` which refuses to exceed the configured budget.
"""

from __future__ import annotations

import html
import logging
import re
import time
from datetime import date
from typing import Any

import requests

from .db import Database

log = logging.getLogger(__name__)

import os

# Base URLs can be overridden (SAM_API_BASE / SAM_SITE_BASE) to point the
# agents at a local stand-in during integration tests.
API_BASE = os.environ.get("SAM_API_BASE", "https://api.sam.gov").rstrip("/")
SITE_BASE = os.environ.get("SAM_SITE_BASE", "https://sam.gov").rstrip("/")
SEARCH_URL = f"{API_BASE}/opportunities/v2/search"
DESCRIPTION_URL = f"{API_BASE}/prod/opportunities/v1/noticedesc"
# sam.gov's own front-end endpoint. Undocumented: used only as a best-effort,
# key-free way to read notice text. Any failure is swallowed.
SITE_OPP_URL = SITE_BASE + "/api/prod/opps/v2/opportunities/{notice_id}"
USER_AGENT = "sam-rfp-radar/0.1 (+https://github.com/uric321-cloud/veil-android)"


class BudgetExhausted(RuntimeError):
    """Raised when the daily SAM.gov request budget would be exceeded."""


class SamGovError(RuntimeError):
    pass


def strip_html(raw: str) -> str:
    """Turn SAM's HTML notice body into readable plain text."""
    if not raw:
        return ""
    text = re.sub(r"(?i)<br\s*/?>|</p>|</div>|</li>|</tr>|</h[1-6]>", "\n", raw)
    text = re.sub(r"(?i)<li[^>]*>", "- ", text)
    text = re.sub(r"<[^>]+>", " ", text)
    text = html.unescape(text).replace("\xa0", " ")
    text = re.sub(r"[ \t\r\f\v]+", " ", text)
    text = "\n".join(line.strip() for line in text.split("\n"))
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def fmt_date(d: date) -> str:
    return d.strftime("%m/%d/%Y")


class SamGovClient:
    def __init__(self, api_key: str, db: Database, daily_budget: int = 10,
                 session: requests.Session | None = None, timeout: float = 60.0):
        self.api_key = api_key
        self.db = db
        self.daily_budget = int(daily_budget)
        self.session = session or requests.Session()
        self.session.headers.setdefault("User-Agent", USER_AGENT)
        self.timeout = timeout

    # ------------------------------------------------------------------ budget
    def remaining_budget(self) -> int:
        return max(0, self.daily_budget - self.db.api_calls_last_24h())

    def _spend(self, endpoint: str) -> None:
        if self.remaining_budget() <= 0:
            raise BudgetExhausted(
                f"SAM.gov daily budget of {self.daily_budget} requests used in the last 24h"
            )

    def _get(self, url: str, params: dict[str, Any], endpoint: str) -> requests.Response:
        if not self.api_key:
            raise SamGovError("SAM_API_KEY is not set")
        self._spend(endpoint)
        params = {**params, "api_key": self.api_key}
        last_exc: Exception | None = None
        for attempt in range(3):
            try:
                resp = self.session.get(url, params=params, timeout=self.timeout)
            except requests.RequestException as exc:  # network trouble: retry
                last_exc = exc
                self.db.record_api_call(endpoint, None, f"error: {exc}")
                time.sleep(2 ** attempt)
                continue
            self.db.record_api_call(endpoint, resp.status_code)
            if resp.status_code == 429:
                raise BudgetExhausted("SAM.gov returned 429 Too Many Requests; daily quota is spent")
            if resp.status_code >= 500 and attempt < 2:
                time.sleep(2 ** attempt)
                continue
            if resp.status_code >= 400:
                raise SamGovError(f"SAM.gov {endpoint} returned {resp.status_code}: {resp.text[:300]}")
            return resp
        raise SamGovError(f"SAM.gov {endpoint} failed after retries: {last_exc}")

    # ------------------------------------------------------------------ search
    def search(self, posted_from: date, posted_to: date, ptype: list[str] | None = None,
               limit: int = 1000, offset: int = 0, **extra: Any) -> dict[str, Any]:
        params: dict[str, Any] = {
            "postedFrom": fmt_date(posted_from),
            "postedTo": fmt_date(posted_to),
            "limit": limit,
            "offset": offset,
        }
        if ptype:
            params["ptype"] = ",".join(ptype)
        params.update({k: v for k, v in extra.items() if v not in (None, "")})
        resp = self._get(SEARCH_URL, params, "search")
        data = resp.json()
        if "opportunitiesData" not in data:
            raise SamGovError(f"Unexpected search payload: {str(data)[:300]}")
        return data

    def iter_search(self, posted_from: date, posted_to: date, ptype: list[str] | None,
                    page_size: int, max_pages: int):
        """Yield pages until SAM runs out of records, the page cap or the budget is hit."""
        offset = 0
        for _ in range(max_pages):
            page = self.search(posted_from, posted_to, ptype, limit=page_size, offset=offset)
            records = page.get("opportunitiesData") or []
            yield page
            total = int(page.get("totalRecords") or 0)
            offset += len(records)
            if not records or offset >= total:
                return

    # ------------------------------------------------------------- description
    def description(self, notice_id: str) -> str:
        resp = self._get(DESCRIPTION_URL, {"noticeid": notice_id}, "description")
        try:
            body = resp.json().get("description", "")
        except ValueError:
            body = resp.text
        return strip_html(body or "")

    def site_description(self, notice_id: str) -> str | None:
        """Key-free notice text from sam.gov's website API. Returns None on any failure."""
        try:
            resp = self.session.get(
                SITE_OPP_URL.format(notice_id=notice_id),
                params={"random": int(time.time() * 1000)},
                headers={"Accept": "application/json"},
                timeout=self.timeout,
            )
            if resp.status_code != 200:
                return None
            payload = resp.json()
        except (requests.RequestException, ValueError):
            return None
        parts: list[str] = []
        desc = payload.get("description")
        if isinstance(desc, list):
            for d in desc:
                if isinstance(d, dict) and d.get("body"):
                    parts.append(str(d["body"]))
        elif isinstance(desc, str):
            parts.append(desc)
        text = strip_html("\n\n".join(parts))
        return text or None


# ------------------------------------------------------------- normalization
def _first(d: dict[str, Any] | None, *keys: str) -> Any:
    if not isinstance(d, dict):
        return None
    for k in keys:
        if d.get(k) not in (None, ""):
            return d[k]
    return None


def normalize(record: dict[str, Any]) -> dict[str, Any]:
    """Map a raw opportunitiesData record to our flat row."""
    import json

    office = record.get("officeAddress") or {}
    pop = record.get("placeOfPerformance") or {}
    pop_city = pop.get("city") or {}
    pop_state = pop.get("state") or {}
    pop_country = pop.get("country") or {}
    pocs = record.get("pointOfContact") or []
    poc_clean = [
        {
            "name": p.get("fullName"),
            "email": p.get("email"),
            "phone": p.get("phone"),
            "title": p.get("title"),
            "type": p.get("type"),
        }
        for p in pocs if isinstance(p, dict)
    ]
    return {
        "notice_id": record.get("noticeId"),
        "solicitation_number": record.get("solicitationNumber"),
        "title": (record.get("title") or "").strip() or "(untitled)",
        "notice_type": record.get("type"),
        "base_type": record.get("baseType"),
        "posted_date": record.get("postedDate"),
        "response_deadline": record.get("responseDeadLine"),
        "archive_date": record.get("archiveDate"),
        "naics_code": str(record.get("naicsCode") or "") or None,
        "psc_code": str(record.get("classificationCode") or "") or None,
        "set_aside_code": record.get("typeOfSetAside"),
        "set_aside": record.get("typeOfSetAsideDescription"),
        "agency_path": record.get("fullParentPathName"),
        "office_city": office.get("city"),
        "office_state": office.get("state"),
        "pop_city": _first(pop_city, "name") if isinstance(pop_city, dict) else pop_city,
        "pop_state": _first(pop_state, "code", "name") if isinstance(pop_state, dict) else pop_state,
        "pop_zip": pop.get("zip"),
        "pop_country": _first(pop_country, "code", "name") if isinstance(pop_country, dict) else pop_country,
        "poc_json": json.dumps(poc_clean),
        "ui_link": record.get("uiLink") or (f"https://sam.gov/opp/{record.get('noticeId')}/view" if record.get("noticeId") else None),
        "description_url": record.get("description") if str(record.get("description") or "").startswith("http") else None,
        "resource_links_json": json.dumps(record.get("resourceLinks") or []),
        "raw_json": json.dumps(record),
        "active": 1 if str(record.get("active", "Yes")).lower() in ("yes", "true", "1") else 0,
    }
