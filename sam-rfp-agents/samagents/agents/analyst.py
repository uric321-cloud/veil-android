"""Analyst agent: Claude reads each candidate notice and explains what the
buyer wants, which products, and how well it fits the business."""

from __future__ import annotations

import json
import logging
from typing import Any, Protocol

from pydantic import BaseModel, Field

from ..config import Settings
from ..db import Database

log = logging.getLogger(__name__)

MAX_DESCRIPTION_CHARS = 14000


class Product(BaseModel):
    name: str = Field(description="Item the buyer wants, e.g. 'Men's hiking boots'")
    quantity: str = Field(default="", description="Quantity with unit, or empty if not stated")
    specs: str = Field(default="", description="Sizes, colors, brand/model, standards, 'or equal' notes")


class OpportunityAnalysis(BaseModel):
    relevant: bool = Field(description="True when this buys physical merchandise we could supply")
    fit_score: int = Field(ge=0, le=100, description="0-100 fit with the business profile")
    category: str = Field(description="One of the configured category names")
    summary: str = Field(description="2-3 plain sentences: who is buying what, for what purpose")
    products: list[Product] = Field(default_factory=list)
    key_requirements: list[str] = Field(
        default_factory=list,
        description="Must-knows: set-aside, Berry/TAA, brand name or equal, delivery deadline, samples, certifications",
    )
    delivery_location: str = Field(default="")
    due_date: str = Field(default="", description="Response deadline as stated, or empty")
    notes: str = Field(default="", description="Why it does or does not fit; red flags")


class AnalystClient(Protocol):
    def analyze(self, system: str, prompt: str) -> OpportunityAnalysis: ...


class ClaudeAnalyst:
    """Thin wrapper over the Anthropic SDK using structured outputs."""

    def __init__(self, api_key: str, model: str, effort: str = "low"):
        import anthropic

        self.client = anthropic.Anthropic(api_key=api_key or None)
        self.model = model
        self.effort = effort

    def analyze(self, system: str, prompt: str) -> OpportunityAnalysis:
        kwargs: dict[str, Any] = dict(
            model=self.model,
            max_tokens=4000,
            system=[{"type": "text", "text": system, "cache_control": {"type": "ephemeral"}}],
            messages=[{"role": "user", "content": prompt}],
            output_format=OpportunityAnalysis,
            output_config={"effort": self.effort},
        )
        # Server-side refusal fallbacks keep a rare policy decline from
        # dropping a notice on the floor; Claude re-runs on a fallback model.
        kwargs["betas"] = ["server-side-fallback-2026-07-01"]
        kwargs["fallbacks"] = "default"
        response = self.client.beta.messages.parse(**kwargs)
        if response.stop_reason == "refusal":
            raise RuntimeError("model declined to analyze this notice")
        parsed = response.parsed_output
        if parsed is None:
            raise RuntimeError("model returned no structured output")
        return parsed


def build_system_prompt(settings: Settings) -> str:
    cats = "\n".join(f"- {c.name}" for c in settings.categories)
    profile = settings.analyst.get("business_profile", "").strip()
    return (
        "You are a federal contracting analyst for a merchandise supplier. You read SAM.gov "
        "contract opportunity notices and decide whether they buy physical goods the business can "
        "supply, then summarize exactly what the buyer wants so the owner can decide in seconds.\n\n"
        f"Business profile:\n{profile}\n\n"
        f"Categories (use exactly one of these names):\n{cats}\n\n"
        "Scoring guide for fit_score: 90-100 a clean merchandise buy squarely in our lines "
        "(HBA, outdoor gear, clothing, accessories) with a reachable set-aside; 70-89 merchandise we "
        "can source but with hurdles (specialized specs, Berry Amendment, large volume, tight deadline); "
        "40-69 general goods outside our core lines or a sources-sought with vague requirements; "
        "0-39 services, construction, labor, equipment leasing, R&D, or a notice we cannot act on. "
        "Set relevant=false whenever the notice is mainly services, construction, installation, labor, "
        "or when the goods are not something a merchandise distributor could supply (aircraft parts, "
        "weapons, pharmaceuticals requiring a license, heavy machinery).\n"
        "Be concrete in products: list each item with quantity and key specs as stated. If the text is "
        "only a title and metadata, say so in notes and score conservatively."
    )


def build_user_prompt(row: Any, settings: Settings) -> str:
    pocs = []
    try:
        pocs = json.loads(row["poc_json"] or "[]")
    except (TypeError, ValueError):
        pass
    poc_text = "; ".join(
        f"{p.get('name') or ''} {p.get('email') or ''} {p.get('phone') or ''}".strip() for p in pocs
    ) or "n/a"
    try:
        links = json.loads(row["resource_links_json"] or "[]")
    except (TypeError, ValueError):
        links = []
    desc = (row["description_text"] or "").strip()
    if len(desc) > MAX_DESCRIPTION_CHARS:
        desc = desc[:MAX_DESCRIPTION_CHARS] + "\n[... truncated ...]"
    if not desc:
        desc = "(No notice text available; only title and metadata.)"
    pop = ", ".join(x for x in [row["pop_city"], row["pop_state"], row["pop_zip"], row["pop_country"]] if x)
    return (
        f"Title: {row['title']}\n"
        f"Notice type: {row['notice_type']} (base: {row['base_type']})\n"
        f"Solicitation #: {row['solicitation_number'] or 'n/a'}\n"
        f"Agency: {row['agency_path'] or 'n/a'}\n"
        f"Posted: {row['posted_date']}   Response deadline: {row['response_deadline'] or 'n/a'}\n"
        f"NAICS: {row['naics_code'] or 'n/a'}   PSC: {row['psc_code'] or 'n/a'}\n"
        f"Set-aside: {row['set_aside'] or row['set_aside_code'] or 'none stated'}\n"
        f"Place of performance: {pop or 'n/a'}\n"
        f"Point of contact: {poc_text}\n"
        f"Attachments: {len(links)} file(s)\n"
        f"Pre-filter category hint: {row['category_hint']}\n"
        f"Link: {row['ui_link']}\n\n"
        f"Notice text:\n{desc}\n"
    )


def run(settings: Settings, db: Database, analyst: AnalystClient, limit: int | None = None) -> dict[str, Any]:
    run_id = db.start_run("analyst")
    cfg = settings.analyst
    model = str(cfg.get("model", "claude-opus-5-5"))
    limit = limit or int(cfg.get("max_per_run", 60))
    stats: dict[str, Any] = {"analyzed": 0, "relevant": 0, "errors": 0}
    system = build_system_prompt(settings)
    valid = set(settings.category_names())
    for row in db.candidates_without_analysis(limit):
        try:
            result = analyst.analyze(system, build_user_prompt(row, settings))
        except Exception as exc:  # noqa: BLE001 - one bad notice must not stop the batch
            log.warning("analysis failed for %s: %s", row["notice_id"], exc)
            db.save_analysis(row["notice_id"], {"relevant": False, "fit_score": 0}, model, error=str(exc)[:500])
            stats["errors"] += 1
            continue
        data = result.model_dump()
        if data["category"] not in valid:
            data["category"] = row["category_hint"] or settings.categories[-1].name
        db.save_analysis(row["notice_id"], data, model)
        stats["analyzed"] += 1
        if data["relevant"]:
            stats["relevant"] += 1
    db.finish_run(run_id, "ok", stats)
    log.info("analyst: %s", stats)
    return stats
