"""24/7 loop: discovery on an interval, analysis right after, instant alerts,
and one daily digest at the configured local time."""

from __future__ import annotations

import logging
import signal
import time
from datetime import datetime, timedelta, timezone
from typing import Any

from .agents import analyst as analyst_agent
from .agents import discovery, enrichment, reporter
from .config import Settings
from .db import Database
from .samgov import SamGovClient

log = logging.getLogger(__name__)


def build_clients(settings: Settings, db: Database) -> tuple[SamGovClient, Any]:
    sam = SamGovClient(settings.sam_api_key, db, daily_budget=int(settings.sam.get("daily_request_budget", 10)))
    analyst = analyst_agent.ClaudeAnalyst(
        settings.anthropic_api_key,
        model=str(settings.analyst.get("model", "claude-opus-5-5")),
        effort=str(settings.analyst.get("effort", "low")),
    )
    return sam, analyst


def discovery_due(settings: Settings, db: Database, now: datetime | None = None) -> bool:
    now = now or datetime.now(timezone.utc)
    hours = float(settings.schedule.get("discovery_every_hours", 6))
    last = db.last_run("discovery", status=None)
    if last is None:
        return True
    started = datetime.fromisoformat(last["started_at"])
    if last["status"] == "running" and now - started < timedelta(hours=2):
        return False  # another process is mid-run
    return now - started >= timedelta(hours=hours)


def cycle(settings: Settings, db: Database, sam: SamGovClient, analyst: Any) -> dict[str, Any]:
    """One discover -> enrich -> analyze -> alert pass."""
    out: dict[str, Any] = {}
    out["discovery"] = discovery.run(settings, db, sam)
    out["enrichment"] = enrichment.run(settings, db, sam)
    out["analyst"] = analyst_agent.run(settings, db, analyst)
    out["alerts"] = reporter.send_alerts(settings, db)
    return out


def run_forever(settings: Settings, db: Database, poll_seconds: int = 60) -> None:
    sam, analyst = build_clients(settings, db)
    stop = {"flag": False}

    def _stop(*_: Any) -> None:
        log.info("shutdown requested")
        stop["flag"] = True

    signal.signal(signal.SIGINT, _stop)
    signal.signal(signal.SIGTERM, _stop)
    log.info("scheduler started: discovery every %sh, daily report at %s %s, SAM budget %s/day",
             settings.schedule.get("discovery_every_hours", 6), settings.schedule.get("report_time_local", "07:00"),
             settings.timezone, settings.sam.get("daily_request_budget", 10))
    while not stop["flag"]:
        try:
            if discovery_due(settings, db):
                cycle(settings, db, sam, analyst)
            if reporter.daily_report_due(settings, db):
                reporter.send_daily(settings, db)
        except Exception:  # noqa: BLE001 - keep the daemon alive
            log.exception("scheduler tick failed")
        for _ in range(poll_seconds):
            if stop["flag"]:
                break
            time.sleep(1)
    log.info("scheduler stopped")
