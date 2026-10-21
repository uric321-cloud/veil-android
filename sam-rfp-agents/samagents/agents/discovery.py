"""Discovery agent: pull newly posted notices from SAM.gov and store them."""

from __future__ import annotations

import logging
from datetime import date, timedelta
from typing import Any

from ..config import Settings
from ..db import Database
from ..filters import prefilter
from ..samgov import BudgetExhausted, SamGovClient, normalize

log = logging.getLogger(__name__)


def run(settings: Settings, db: Database, client: SamGovClient, today: date | None = None) -> dict[str, Any]:
    cfg = settings.sam
    today = today or date.today()
    posted_from = today - timedelta(days=int(cfg.get("lookback_days", 2)))
    run_id = db.start_run("discovery")
    stats: dict[str, Any] = {"pages": 0, "records": 0, "new": 0, "candidates": 0, "excluded": 0,
                             "window": f"{posted_from}..{today}", "budget_left_before": client.remaining_budget()}
    status = "ok"
    try:
        for page in client.iter_search(
            posted_from, today,
            ptype=[str(p) for p in cfg.get("notice_types", ["o", "k", "p", "r"])],
            page_size=int(cfg.get("page_size", 1000)),
            max_pages=int(cfg.get("max_pages_per_run", 2)),
        ):
            stats["pages"] += 1
            for record in page.get("opportunitiesData") or []:
                if not record.get("noticeId"):
                    continue
                opp = normalize(record)
                st, reason, category = prefilter(opp, settings.filters, settings.categories)
                opp.update({"prefilter_status": st, "prefilter_reason": reason, "category_hint": category})
                is_new = db.upsert_opportunity(opp)
                stats["records"] += 1
                if is_new:
                    stats["new"] += 1
                    stats["candidates" if st == "candidate" else "excluded"] += 1
    except BudgetExhausted as exc:
        log.warning("discovery stopped: %s", exc)
        stats["budget_exhausted"] = str(exc)
        status = "ok" if stats["pages"] else "budget"
    except Exception as exc:  # noqa: BLE001 - recorded and surfaced via run status
        log.exception("discovery failed")
        stats["error"] = str(exc)
        status = "error"
    stats["budget_left_after"] = client.remaining_budget()
    db.finish_run(run_id, status, stats)
    log.info("discovery: %s", stats)
    return stats
