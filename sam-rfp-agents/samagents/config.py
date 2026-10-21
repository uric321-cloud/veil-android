"""Configuration: config.yaml for behaviour, environment variables for secrets."""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml
from dotenv import load_dotenv


@dataclass
class Category:
    name: str
    psc_prefixes: list[str] = field(default_factory=list)
    keywords: list[str] = field(default_factory=list)


@dataclass
class EmailSettings:
    provider: str
    sender: str
    recipients: list[str]
    smtp_host: str
    smtp_port: int
    smtp_user: str
    smtp_password: str
    resend_api_key: str
    subject_prefix: str
    attach_csv: bool

    @property
    def configured(self) -> bool:
        if not self.sender or not self.recipients:
            return False
        if self.provider == "resend":
            return bool(self.resend_api_key)
        return bool(self.smtp_host)


@dataclass
class Settings:
    raw: dict[str, Any]
    sam_api_key: str
    anthropic_api_key: str
    database_path: Path
    email: EmailSettings
    categories: list[Category]

    @property
    def sam(self) -> dict[str, Any]:
        return self.raw.get("sam", {})

    @property
    def schedule(self) -> dict[str, Any]:
        return self.raw.get("schedule", {})

    @property
    def filters(self) -> dict[str, Any]:
        return self.raw.get("filters", {})

    @property
    def analyst(self) -> dict[str, Any]:
        return self.raw.get("analyst", {})

    @property
    def timezone(self) -> str:
        return self.schedule.get("timezone", "UTC")

    def category_names(self) -> list[str]:
        return [c.name for c in self.categories]


def _split_list(value: str) -> list[str]:
    return [v.strip() for v in value.split(",") if v.strip()]


def load_settings(config_path: str | os.PathLike | None = None, env_path: str | os.PathLike | None = ".env") -> Settings:
    """Load config.yaml and .env. Missing files fall back to defaults so tests
    and dry runs work without any setup."""
    if env_path and Path(env_path).exists():
        load_dotenv(env_path, override=False)

    path = Path(config_path or os.environ.get("CONFIG_PATH", "config.yaml"))
    raw: dict[str, Any] = {}
    if path.exists():
        with open(path, encoding="utf-8") as fh:
            raw = yaml.safe_load(fh) or {}

    categories = [
        Category(
            name=c["name"],
            psc_prefixes=[str(p) for p in c.get("psc_prefixes", [])],
            keywords=[str(k).lower() for k in c.get("keywords", [])],
        )
        for c in raw.get("categories", [])
    ]
    if not categories:
        categories = [Category(name="Other Merchandise")]

    email_cfg = raw.get("email", {})
    email = EmailSettings(
        provider=os.environ.get("EMAIL_PROVIDER", "smtp").lower(),
        sender=os.environ.get("EMAIL_FROM", ""),
        recipients=_split_list(os.environ.get("EMAIL_TO", "")),
        smtp_host=os.environ.get("SMTP_HOST", ""),
        smtp_port=int(os.environ.get("SMTP_PORT", "587") or 587),
        smtp_user=os.environ.get("SMTP_USER", ""),
        smtp_password=os.environ.get("SMTP_PASSWORD", ""),
        resend_api_key=os.environ.get("RESEND_API_KEY", ""),
        subject_prefix=email_cfg.get("subject_prefix", "[SAM.gov RFP Radar]"),
        attach_csv=bool(email_cfg.get("attach_csv", True)),
    )

    return Settings(
        raw=raw,
        sam_api_key=os.environ.get("SAM_API_KEY", ""),
        anthropic_api_key=os.environ.get("ANTHROPIC_API_KEY", ""),
        database_path=Path(os.environ.get("DATABASE_PATH", "data/opportunities.sqlite")),
        email=email,
        categories=categories,
    )
