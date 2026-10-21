"""End-to-end: discovery -> enrichment -> analyst -> reports with fakes."""

from datetime import date, datetime, timezone

from samagents.agents import analyst as analyst_agent
from samagents.agents import discovery, enrichment, reporter
from samagents.agents.analyst import OpportunityAnalysis, Product
from samagents.samgov import SamGovClient

from .test_samgov import FakeResponse, FakeSession


class FakeAnalyst:
    def __init__(self):
        self.prompts = []

    def analyze(self, system, prompt):
        self.prompts.append(prompt)
        if "Title: Cold Weather Parkas" in prompt:
            return OpportunityAnalysis(
                relevant=True, fit_score=92, category="Clothing & Footwear",
                summary="Army Natick wants cold weather parkas and insulated trousers for Fort Drum.",
                products=[Product(name="Cold weather parka", quantity="1,200 ea", specs="sizes S-XXL, coyote brown, or equal"),
                          Product(name="Insulated trousers", quantity="1,200 ea", specs="matching")],
                key_requirements=["Total small business set-aside", "Berry Amendment compliant"],
                delivery_location="Fort Drum, NY", due_date="2026-11-04", notes="Strong fit.")
        if "Title: Personal Hygiene" in prompt:
            return OpportunityAnalysis(relevant=True, fit_score=78, category="Health & Beauty Aids",
                                       summary="Air Force wants deployment hygiene kits.", products=[Product(name="Hygiene kit", quantity="3,000")])
        if "Title: Purchase of Hiking Backpacks" in prompt:
            return OpportunityAnalysis(relevant=True, fit_score=70, category="Not A Real Category",
                                       summary="NPS youth program backpacks and sleeping bags.")
        return OpportunityAnalysis(relevant=False, fit_score=30, category="Other Merchandise", summary="Vague presolicitation.")


def _client(db, search_payload, budget=10):
    session = FakeSession([
        FakeResponse(200, search_payload),
        FakeResponse(200, {"description": "<p>Parkas: <b>1,200</b> each, sizes S-XXL.</p>"}),
        FakeResponse(200, {"description": "<p>Hygiene kits for 3,000 airmen.</p>"}),
        FakeResponse(200, {"description": "<p>Backpacks 60L and 20F sleeping bags.</p>"}),
        FakeResponse(200, {"description": ""}),
        FakeResponse(200, {"description": [{"body": "Site text for MWR fitness gear."}]}),
    ])
    return SamGovClient("key", db, daily_budget=budget, session=session), session


def test_full_pipeline(settings, db, search_payload):
    settings.raw["sam"]["enrich_max_per_run"] = 4
    client, session = _client(db, search_payload)

    stats = discovery.run(settings, db, client, today=date(2026, 10, 21))
    assert stats["records"] == 6 and stats["new"] == 6
    assert stats["candidates"] == 4 and stats["excluded"] == 2
    assert db.api_calls_last_24h() == 1

    # Second discovery with the same payload creates nothing new.
    session.responses.insert(0, FakeResponse(200, search_payload))
    stats2 = discovery.run(settings, db, client, today=date(2026, 10, 21))
    assert stats2["new"] == 0

    e = enrichment.run(settings, db, client)
    assert e["api"] == 3  # the fourth API answer was empty, so the site fallback supplied it
    assert e["site"] == 1
    assert db.counts()["with_description"] == 4

    fake = FakeAnalyst()
    a = analyst_agent.run(settings, db, fake)
    assert a["analyzed"] == 4 and a["relevant"] == 3 and a["errors"] == 0
    assert any("Notice text:" in p and "Parkas" in p for p in fake.prompts)
    row = db.get_opportunity("e5f60718293a4b5c6d7e8f90a1b2c3d4")
    assert row["category"] == "Outdoor & Tactical Gear"  # invalid model category fell back to hint

    sent = []

    def fake_sender(email, subject, html_body, text_body, csv_bytes):
        sent.append((subject, html_body, text_body, csv_bytes))

    alerts = reporter.send_alerts(settings, db, sender=fake_sender)
    assert alerts["count"] == 1 and sent[-1][0].startswith("[SAM.gov RFP Radar] HOT: Cold Weather Parkas")

    daily = reporter.send_daily(settings, db, sender=fake_sender)
    assert daily["count"] == 3 and daily["sent"]
    subject, html_body, text_body, csv_bytes = sent[-1]
    assert "3 new merchandise opportunities" in subject
    assert "Clothing &amp; Footwear (1)" in html_body
    assert "Health &amp; Beauty Aids (1)" in html_body
    assert "Fit 92/100" in html_body
    assert "Insulated trousers" in html_body
    assert "Berry Amendment" in html_body
    assert "sam.gov/opp/a1b2c3d4e5f60718293a4b5c6d7e8f90/view" in html_body
    assert "[92/100] Cold Weather Parkas" in text_body
    assert csv_bytes and b"Cold Weather Parkas" in csv_bytes

    # Nothing left to report; a second run is a no-op.
    assert reporter.send_daily(settings, db, sender=fake_sender)["skipped"] == "nothing new"
    assert db.counts()["reports_sent"] == 2


def test_daily_report_due_logic(settings, db):
    from zoneinfo import ZoneInfo
    tz = ZoneInfo(settings.timezone)
    assert not reporter.daily_report_due(settings, db, now=datetime(2026, 10, 21, 6, 59, tzinfo=tz))
    assert reporter.daily_report_due(settings, db, now=datetime(2026, 10, 21, 7, 0, tzinfo=tz))
    db.record_report("daily", ["a@b"], "s", [], "sent")
    assert not reporter.daily_report_due(settings, db, now=datetime.now(tz))
    assert reporter.daily_report_due(settings, db, now=datetime.now(tz).replace(year=2099))


def test_analyst_failure_is_recorded_not_fatal(settings, db, search_payload):
    client, _ = _client(db, search_payload)
    discovery.run(settings, db, client, today=date(2026, 10, 21))

    class Boom:
        def analyze(self, system, prompt):
            raise RuntimeError("api down")

    stats = analyst_agent.run(settings, db, Boom())
    assert stats["errors"] == 4 and stats["analyzed"] == 0
    # Errored rows are retried on the next run.
    assert len(db.candidates_without_analysis(50)) == 4


def test_discovery_survives_budget_exhaustion(settings, db, search_payload):
    client, _ = _client(db, search_payload, budget=0)
    stats = discovery.run(settings, db, client, today=date(2026, 10, 21))
    assert stats["pages"] == 0 and "budget_exhausted" in stats
    assert db.last_run("discovery", status=None)["status"] == "budget"


def test_email_not_configured_is_skipped(settings, db, monkeypatch):
    settings.email.smtp_host = ""
    out = reporter.send_daily(settings, db, force=True)
    assert out["skipped"] == "email not configured"
