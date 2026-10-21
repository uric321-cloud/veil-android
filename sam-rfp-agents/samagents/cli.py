"""Command line entry point: python -m samagents <command>."""

from __future__ import annotations

import argparse
import json
import logging
import sys
from datetime import datetime
from zoneinfo import ZoneInfo

from . import __version__, scheduler
from .agents import analyst as analyst_agent
from .agents import discovery, enrichment, reporter
from .config import Settings, load_settings
from .db import Database


def _setup_logging(verbose: bool) -> None:
    logging.basicConfig(
        level=logging.DEBUG if verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        stream=sys.stderr,
    )
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("urllib3").setLevel(logging.WARNING)


def _open(args: argparse.Namespace) -> tuple[Settings, Database]:
    settings = load_settings(args.config, args.env)
    return settings, Database(args.db or settings.database_path)


def cmd_run(args: argparse.Namespace) -> int:
    settings, db = _open(args)
    scheduler.run_forever(settings, db)
    return 0


def cmd_once(args: argparse.Namespace) -> int:
    """One full pass. With --report-if-due also sends the daily digest when its time has come."""
    settings, db = _open(args)
    sam, analyst = scheduler.build_clients(settings, db)
    out = scheduler.cycle(settings, db, sam, analyst)
    if args.report_if_due and reporter.daily_report_due(settings, db):
        out["daily"] = reporter.send_daily(settings, db)
    print(json.dumps(out, indent=2))
    return 0


def cmd_discover(args: argparse.Namespace) -> int:
    settings, db = _open(args)
    sam, _ = scheduler.build_clients(settings, db)
    print(json.dumps(discovery.run(settings, db, sam), indent=2))
    return 0


def cmd_enrich(args: argparse.Namespace) -> int:
    settings, db = _open(args)
    sam, _ = scheduler.build_clients(settings, db)
    print(json.dumps(enrichment.run(settings, db, sam), indent=2))
    return 0


def cmd_analyze(args: argparse.Namespace) -> int:
    settings, db = _open(args)
    _, analyst = scheduler.build_clients(settings, db)
    print(json.dumps(analyst_agent.run(settings, db, analyst, limit=args.limit), indent=2))
    return 0


def cmd_report(args: argparse.Namespace) -> int:
    settings, db = _open(args)
    if args.if_due and not reporter.daily_report_due(settings, db):
        print(json.dumps({"skipped": "daily report not due yet"}))
        return 0
    if args.preview:
        rows = db.unreported("daily", int(settings.analyst.get("min_fit_score_for_report", 40)))
        html = reporter.render_report_html(rows, settings, f"Preview: {len(rows)} opportunities", "Preview only; nothing was marked as reported.")
        with open(args.preview, "w", encoding="utf-8") as fh:
            fh.write(html)
        print(f"wrote {args.preview} with {len(rows)} items")
        return 0
    print(json.dumps(reporter.send_daily(settings, db, force=args.force), indent=2))
    return 0


def cmd_alerts(args: argparse.Namespace) -> int:
    settings, db = _open(args)
    print(json.dumps(reporter.send_alerts(settings, db), indent=2))
    return 0


def cmd_status(args: argparse.Namespace) -> int:
    settings, db = _open(args)
    sam, _ = scheduler.build_clients(settings, db)
    info = {
        "version": __version__,
        "database": str(db.path),
        "counts": db.counts(),
        "sam_requests_last_24h": db.api_calls_last_24h(),
        "sam_budget_remaining": sam.remaining_budget(),
        "local_time": datetime.now(ZoneInfo(settings.timezone)).isoformat(),
        "email_configured": settings.email.configured,
        "anthropic_key_set": bool(settings.anthropic_api_key),
        "sam_key_set": bool(settings.sam_api_key),
    }
    for agent in ("discovery", "enrichment", "analyst"):
        last = db.last_run(agent, status=None)
        info[f"last_{agent}"] = dict(last) if last else None
    last_daily = db.last_report("daily")
    info["last_daily_report"] = dict(last_daily) if last_daily else None
    print(json.dumps(info, indent=2, default=str))
    return 0


def cmd_show(args: argparse.Namespace) -> int:
    settings, db = _open(args)
    row = db.get_opportunity(args.notice_id)
    if not row:
        print("not found")
        return 1
    d = {k: row[k] for k in row.keys() if k != "raw_json"}
    print(json.dumps(d, indent=2, default=str))
    return 0


def cmd_export(args: argparse.Namespace) -> int:
    settings, db = _open(args)
    rows = db.export_rows(args.min_score)
    data = reporter.render_csv(rows)
    with open(args.out, "wb") as fh:
        fh.write(data)
    print(f"wrote {len(rows)} rows to {args.out}")
    return 0


def cmd_test_email(args: argparse.Namespace) -> int:
    settings, db = _open(args)
    email = settings.email
    if not email.configured:
        print("email is not configured; check EMAIL_FROM, EMAIL_TO and SMTP_*/RESEND_API_KEY in .env")
        return 1
    sender = reporter.pick_sender(email)
    sender(email, f"{email.subject_prefix} test message", "<p>SAM.gov RFP Radar can send email.</p>",
           "SAM.gov RFP Radar can send email.", None)
    print(f"test email sent to {email.recipients}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="samagents", description="SAM.gov RFP Radar agents")
    p.add_argument("--config", default=None, help="config.yaml path (default: CONFIG_PATH or ./config.yaml)")
    p.add_argument("--env", default=".env", help=".env path")
    p.add_argument("--db", default=None, help="SQLite path (default: DATABASE_PATH)")
    p.add_argument("-v", "--verbose", action="store_true")
    sub = p.add_subparsers(dest="command", required=True)

    sub.add_parser("run", help="run the 24/7 scheduler").set_defaults(func=cmd_run)
    s = sub.add_parser("once", help="one discover/enrich/analyze/alert pass (for cron or CI)")
    s.add_argument("--report-if-due", action="store_true")
    s.set_defaults(func=cmd_once)
    sub.add_parser("discover", help="pull new notices from SAM.gov").set_defaults(func=cmd_discover)
    sub.add_parser("enrich", help="fetch notice text for candidates").set_defaults(func=cmd_enrich)
    s = sub.add_parser("analyze", help="run the Claude analyst on unanalyzed candidates")
    s.add_argument("--limit", type=int, default=None)
    s.set_defaults(func=cmd_analyze)
    s = sub.add_parser("report", help="send the daily digest")
    s.add_argument("--force", action="store_true", help="send even when nothing is new")
    s.add_argument("--if-due", action="store_true", help="only send when today's digest has not gone out")
    s.add_argument("--preview", metavar="FILE.html", help="write the HTML to a file instead of sending")
    s.set_defaults(func=cmd_report)
    sub.add_parser("alerts", help="send instant alerts for high-fit items").set_defaults(func=cmd_alerts)
    sub.add_parser("status", help="show counts, budget and last runs").set_defaults(func=cmd_status)
    s = sub.add_parser("show", help="print one opportunity with its analysis")
    s.add_argument("notice_id")
    s.set_defaults(func=cmd_show)
    s = sub.add_parser("export", help="export relevant opportunities to CSV")
    s.add_argument("--out", default="opportunities.csv")
    s.add_argument("--min-score", type=int, default=0)
    s.set_defaults(func=cmd_export)
    sub.add_parser("test-email", help="send a test email").set_defaults(func=cmd_test_email)
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    _setup_logging(args.verbose)
    return int(args.func(args))
