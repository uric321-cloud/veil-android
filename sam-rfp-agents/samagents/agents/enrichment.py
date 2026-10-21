"""Enrichment agent: attach the full notice text to candidate opportunities."""

from __future__ import annotations

import logging
from typing import Any

from ..config import Settings
from ..db import Database
from ..samgov import BudgetExhausted, SamGovClient, SamGovError

log = logging.getLogger(__name__)


def run(settings: Settings, db: Database, client: SamGovClient) -> dict[str, Any]:
    cfg = settings.sam
    run_id = db.start_run("enrichment")
    stats: dict[str, Any] = {"considered": 0, "api": 0, "site": 0, "missing": 0}
    if not cfg.get("enrich_descriptions", True):
        db.finish_run(run_id, "ok", {"skipped": "enrich_descriptions=false"})
        return stats

    api_quota = int(cfg.get("enrich_max_per_run", 4))
    use_site = bool(cfg.get("site_fallback", True))
    # Look at more rows than the API quota so the free site path can cover the rest.
    rows = db.candidates_without_description(limit=max(api_quota, 25))
    for row in rows:
        stats["considered"] += 1
        text: str | None = None
        source = ""
        if api_quota > 0 and client.remaining_budget() > 1:  # keep one call for the next search
            try:
                text = client.description(row["notice_id"]) or None
                source = "api"
                api_quota -= 1
            except BudgetExhausted:
                api_quota = 0
            except SamGovError as exc:
                log.warning("description %s failed: %s", row["notice_id"], exc)
                api_quota -= 1
        if not text and use_site:
            text = client.site_description(row["notice_id"])
            source = "site" if text else source
        if text:
            db.set_description(row["notice_id"], text, source)
            stats[source] += 1
        else:
            stats["missing"] += 1
    db.finish_run(run_id, "ok", stats)
    log.info("enrichment: %s", stats)
    return stats
