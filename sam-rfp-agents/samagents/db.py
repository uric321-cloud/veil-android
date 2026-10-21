"""SQLite storage for opportunities, analyses, reports and API usage."""

from __future__ import annotations

import json
import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Iterable

SCHEMA = """
CREATE TABLE IF NOT EXISTS opportunities (
    notice_id            TEXT PRIMARY KEY,
    solicitation_number  TEXT,
    title                TEXT NOT NULL,
    notice_type          TEXT,
    base_type            TEXT,
    posted_date          TEXT,
    response_deadline    TEXT,
    archive_date         TEXT,
    naics_code           TEXT,
    psc_code             TEXT,
    set_aside_code       TEXT,
    set_aside            TEXT,
    agency_path          TEXT,
    office_city          TEXT,
    office_state         TEXT,
    pop_city             TEXT,
    pop_state            TEXT,
    pop_zip              TEXT,
    pop_country          TEXT,
    poc_json             TEXT,
    ui_link              TEXT,
    description_url      TEXT,
    resource_links_json  TEXT,
    raw_json             TEXT,
    description_text     TEXT,
    description_source   TEXT,
    prefilter_status     TEXT NOT NULL DEFAULT 'candidate',
    prefilter_reason     TEXT,
    category_hint        TEXT,
    active               INTEGER NOT NULL DEFAULT 1,
    first_seen_at        TEXT NOT NULL,
    last_seen_at         TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_opp_prefilter ON opportunities(prefilter_status);
CREATE INDEX IF NOT EXISTS idx_opp_posted ON opportunities(posted_date);

CREATE TABLE IF NOT EXISTS analyses (
    notice_id          TEXT PRIMARY KEY REFERENCES opportunities(notice_id),
    relevant           INTEGER NOT NULL,
    fit_score          INTEGER NOT NULL,
    category           TEXT,
    summary            TEXT,
    products_json      TEXT,
    key_requirements_json TEXT,
    delivery_location  TEXT,
    due_date           TEXT,
    notes              TEXT,
    model              TEXT,
    analyzed_at        TEXT NOT NULL,
    error              TEXT
);

CREATE TABLE IF NOT EXISTS reports (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    kind          TEXT NOT NULL,          -- daily | alert
    sent_at       TEXT NOT NULL,
    recipients    TEXT,
    subject       TEXT,
    notice_count  INTEGER NOT NULL,
    status        TEXT NOT NULL,          -- sent | failed | skipped
    error         TEXT
);

CREATE TABLE IF NOT EXISTS report_items (
    report_id  INTEGER NOT NULL REFERENCES reports(id),
    notice_id  TEXT NOT NULL,
    kind       TEXT NOT NULL,
    PRIMARY KEY (report_id, notice_id)
);
CREATE INDEX IF NOT EXISTS idx_report_items_notice ON report_items(notice_id, kind);

CREATE TABLE IF NOT EXISTS api_calls (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    ts          TEXT NOT NULL,
    endpoint    TEXT NOT NULL,
    status_code INTEGER,
    note        TEXT
);
CREATE INDEX IF NOT EXISTS idx_api_calls_ts ON api_calls(ts);

CREATE TABLE IF NOT EXISTS runs (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    agent       TEXT NOT NULL,
    started_at  TEXT NOT NULL,
    finished_at TEXT,
    status      TEXT NOT NULL,
    stats_json  TEXT
);
CREATE INDEX IF NOT EXISTS idx_runs_agent ON runs(agent, started_at);
"""


def utcnow() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


class Database:
    def __init__(self, path: str | Path):
        self.path = Path(path)
        if str(self.path) != ":memory:":
            self.path.parent.mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(str(self.path), check_same_thread=False)
        self.conn.row_factory = sqlite3.Row
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute("PRAGMA foreign_keys=ON")
        self.conn.executescript(SCHEMA)

    def close(self) -> None:
        self.conn.close()

    # ------------------------------------------------------------ opportunities
    def upsert_opportunity(self, opp: dict[str, Any]) -> bool:
        """Insert or refresh a normalized opportunity. Returns True when new."""
        now = utcnow()
        existing = self.conn.execute(
            "SELECT notice_id FROM opportunities WHERE notice_id = ?", (opp["notice_id"],)
        ).fetchone()
        cols = [
            "solicitation_number", "title", "notice_type", "base_type", "posted_date",
            "response_deadline", "archive_date", "naics_code", "psc_code", "set_aside_code",
            "set_aside", "agency_path", "office_city", "office_state", "pop_city", "pop_state",
            "pop_zip", "pop_country", "poc_json", "ui_link", "description_url",
            "resource_links_json", "raw_json", "prefilter_status", "prefilter_reason",
            "category_hint", "active",
        ]
        values = [opp.get(c) for c in cols]
        if existing:
            sets = ", ".join(f"{c} = ?" for c in cols)
            self.conn.execute(
                f"UPDATE opportunities SET {sets}, last_seen_at = ? WHERE notice_id = ?",
                [*values, now, opp["notice_id"]],
            )
            self.conn.commit()
            return False
        placeholders = ", ".join("?" for _ in cols)
        self.conn.execute(
            f"INSERT INTO opportunities (notice_id, {', '.join(cols)}, first_seen_at, last_seen_at) "
            f"VALUES (?, {placeholders}, ?, ?)",
            [opp["notice_id"], *values, now, now],
        )
        self.conn.commit()
        return True

    def get_opportunity(self, notice_id: str) -> sqlite3.Row | None:
        return self.conn.execute(
            "SELECT o.*, a.fit_score, a.relevant, a.category, a.summary, a.products_json, "
            "a.key_requirements_json, a.delivery_location, a.due_date, a.notes "
            "FROM opportunities o LEFT JOIN analyses a USING (notice_id) WHERE notice_id = ?",
            (notice_id,),
        ).fetchone()

    def set_description(self, notice_id: str, text: str, source: str) -> None:
        self.conn.execute(
            "UPDATE opportunities SET description_text = ?, description_source = ? WHERE notice_id = ?",
            (text, source, notice_id),
        )
        self.conn.commit()

    def candidates_without_description(self, limit: int) -> list[sqlite3.Row]:
        return self.conn.execute(
            "SELECT * FROM opportunities WHERE prefilter_status = 'candidate' "
            "AND description_text IS NULL AND notice_id NOT IN (SELECT notice_id FROM analyses) "
            "ORDER BY posted_date DESC, first_seen_at DESC LIMIT ?",
            (limit,),
        ).fetchall()

    def candidates_without_analysis(self, limit: int) -> list[sqlite3.Row]:
        return self.conn.execute(
            "SELECT * FROM opportunities WHERE prefilter_status = 'candidate' "
            "AND notice_id NOT IN (SELECT notice_id FROM analyses WHERE error IS NULL) "
            "ORDER BY posted_date DESC, first_seen_at DESC LIMIT ?",
            (limit,),
        ).fetchall()

    # ---------------------------------------------------------------- analyses
    def save_analysis(self, notice_id: str, result: dict[str, Any], model: str, error: str | None = None) -> None:
        self.conn.execute(
            "INSERT INTO analyses (notice_id, relevant, fit_score, category, summary, products_json, "
            "key_requirements_json, delivery_location, due_date, notes, model, analyzed_at, error) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
            "ON CONFLICT(notice_id) DO UPDATE SET relevant=excluded.relevant, fit_score=excluded.fit_score, "
            "category=excluded.category, summary=excluded.summary, products_json=excluded.products_json, "
            "key_requirements_json=excluded.key_requirements_json, delivery_location=excluded.delivery_location, "
            "due_date=excluded.due_date, notes=excluded.notes, model=excluded.model, "
            "analyzed_at=excluded.analyzed_at, error=excluded.error",
            (
                notice_id,
                1 if result.get("relevant") else 0,
                int(result.get("fit_score") or 0),
                result.get("category"),
                result.get("summary"),
                json.dumps(result.get("products") or []),
                json.dumps(result.get("key_requirements") or []),
                result.get("delivery_location"),
                result.get("due_date"),
                result.get("notes"),
                model,
                utcnow(),
                error,
            ),
        )
        self.conn.commit()

    # ----------------------------------------------------------------- reports
    def unreported(self, kind: str, min_score: int) -> list[sqlite3.Row]:
        """Analyzed, relevant opportunities not yet included in a sent report of `kind`."""
        return self.conn.execute(
            "SELECT o.*, a.fit_score, a.relevant, a.category, a.summary, a.products_json, "
            "a.key_requirements_json, a.delivery_location, a.due_date, a.notes "
            "FROM opportunities o JOIN analyses a USING (notice_id) "
            "WHERE a.error IS NULL AND a.relevant = 1 AND a.fit_score >= ? "
            "AND o.notice_id NOT IN (SELECT ri.notice_id FROM report_items ri "
            "    JOIN reports r ON r.id = ri.report_id WHERE ri.kind = ? AND r.status = 'sent') "
            "ORDER BY a.fit_score DESC, o.response_deadline ASC",
            (min_score, kind),
        ).fetchall()

    def record_report(self, kind: str, recipients: Iterable[str], subject: str, notice_ids: Iterable[str],
                      status: str, error: str | None = None) -> int:
        ids = list(notice_ids)
        cur = self.conn.execute(
            "INSERT INTO reports (kind, sent_at, recipients, subject, notice_count, status, error) "
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            (kind, utcnow(), ",".join(recipients), subject, len(ids), status, error),
        )
        report_id = int(cur.lastrowid)
        self.conn.executemany(
            "INSERT OR IGNORE INTO report_items (report_id, notice_id, kind) VALUES (?, ?, ?)",
            [(report_id, nid, kind) for nid in ids],
        )
        self.conn.commit()
        return report_id

    def last_report(self, kind: str, status: str = "sent") -> sqlite3.Row | None:
        return self.conn.execute(
            "SELECT * FROM reports WHERE kind = ? AND status = ? ORDER BY sent_at DESC LIMIT 1",
            (kind, status),
        ).fetchone()

    # --------------------------------------------------------------- api usage
    def record_api_call(self, endpoint: str, status_code: int | None, note: str = "") -> None:
        self.conn.execute(
            "INSERT INTO api_calls (ts, endpoint, status_code, note) VALUES (?, ?, ?, ?)",
            (utcnow(), endpoint, status_code, note),
        )
        self.conn.commit()

    def api_calls_last_24h(self) -> int:
        since = (datetime.now(timezone.utc) - timedelta(hours=24)).isoformat()
        row = self.conn.execute("SELECT COUNT(*) AS n FROM api_calls WHERE ts >= ?", (since,)).fetchone()
        return int(row["n"])

    # -------------------------------------------------------------------- runs
    def start_run(self, agent: str) -> int:
        cur = self.conn.execute(
            "INSERT INTO runs (agent, started_at, status) VALUES (?, ?, 'running')", (agent, utcnow())
        )
        self.conn.commit()
        return int(cur.lastrowid)

    def finish_run(self, run_id: int, status: str, stats: dict[str, Any] | None = None) -> None:
        self.conn.execute(
            "UPDATE runs SET finished_at = ?, status = ?, stats_json = ? WHERE id = ?",
            (utcnow(), status, json.dumps(stats or {}), run_id),
        )
        self.conn.commit()

    def last_run(self, agent: str, status: str | None = "ok") -> sqlite3.Row | None:
        if status:
            return self.conn.execute(
                "SELECT * FROM runs WHERE agent = ? AND status = ? ORDER BY started_at DESC LIMIT 1",
                (agent, status),
            ).fetchone()
        return self.conn.execute(
            "SELECT * FROM runs WHERE agent = ? ORDER BY started_at DESC LIMIT 1", (agent,)
        ).fetchone()

    # ------------------------------------------------------------------ status
    def counts(self) -> dict[str, int]:
        q = {
            "opportunities": "SELECT COUNT(*) FROM opportunities",
            "candidates": "SELECT COUNT(*) FROM opportunities WHERE prefilter_status = 'candidate'",
            "excluded": "SELECT COUNT(*) FROM opportunities WHERE prefilter_status = 'excluded'",
            "with_description": "SELECT COUNT(*) FROM opportunities WHERE description_text IS NOT NULL",
            "analyzed": "SELECT COUNT(*) FROM analyses WHERE error IS NULL",
            "relevant": "SELECT COUNT(*) FROM analyses WHERE error IS NULL AND relevant = 1",
            "reports_sent": "SELECT COUNT(*) FROM reports WHERE status = 'sent'",
        }
        return {k: int(self.conn.execute(sql).fetchone()[0]) for k, sql in q.items()}

    def export_rows(self, min_score: int = 0) -> list[sqlite3.Row]:
        return self.conn.execute(
            "SELECT o.*, a.fit_score, a.relevant, a.category, a.summary, a.products_json, "
            "a.key_requirements_json, a.delivery_location, a.due_date, a.notes "
            "FROM opportunities o JOIN analyses a USING (notice_id) "
            "WHERE a.error IS NULL AND a.relevant = 1 AND a.fit_score >= ? "
            "ORDER BY o.posted_date DESC, a.fit_score DESC",
            (min_score,),
        ).fetchall()
