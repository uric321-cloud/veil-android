"""Cheap, deterministic pre-filter run before anything costs an API call.

Goal: keep notices that buy merchandise, drop construction/labor/services.
The Product Service Code (PSC) is the strongest signal: product codes start
with a digit, service codes with a letter (Y/Z construction, R services...).
"""

from __future__ import annotations

from typing import Any

from .config import Category


def _lower(s: Any) -> str:
    return str(s or "").lower()


def categorize(psc: str | None, title: str, categories: list[Category]) -> str:
    """First category whose PSC prefix or keyword matches; last category is the catch-all."""
    psc = (psc or "").upper()
    t = title.lower()
    for cat in categories:
        if any(psc.startswith(p.upper()) for p in cat.psc_prefixes if p):
            return cat.name
    for cat in categories:
        if any(k in t for k in cat.keywords):
            return cat.name
    return categories[-1].name if categories else "Other Merchandise"


def prefilter(opp: dict[str, Any], filters: dict[str, Any], categories: list[Category]) -> tuple[str, str, str]:
    """Return (status, reason, category_hint). status is 'candidate' or 'excluded'."""
    title = _lower(opp.get("title"))
    psc = (opp.get("psc_code") or "").strip().upper()
    naics = (opp.get("naics_code") or "").strip()
    category = categorize(psc, title, categories)

    for kw in filters.get("exclude_title_keywords", []):
        if _lower(kw) in title:
            return "excluded", f"title matches '{kw}'", category

    for prefix in filters.get("exclude_naics_prefixes", []):
        if naics and naics.startswith(str(prefix)):
            return "excluded", f"NAICS {naics} is excluded ({prefix}*)", category

    rescued = any(_lower(kw) in title for kw in filters.get("include_title_keywords", []))

    for prefix in filters.get("exclude_psc_prefixes", []):
        if psc and psc.startswith(str(prefix).upper()):
            if rescued:
                return "candidate", f"service PSC {psc} but title buys goods", category
            return "excluded", f"PSC {psc} is a service code", category

    if filters.get("product_only", True):
        if psc and psc[0].isdigit():
            return "candidate", f"product PSC {psc}", category
        if not psc:
            if rescued:
                return "candidate", "no PSC; title buys goods", category
            return "excluded", "no PSC and title gives no product signal", category
        # Letter-led PSC not in the exclusion list (rare).
        if rescued:
            return "candidate", f"PSC {psc} but title buys goods", category
        return "excluded", f"PSC {psc} is not a product code", category

    return "candidate", "product_only disabled", category
