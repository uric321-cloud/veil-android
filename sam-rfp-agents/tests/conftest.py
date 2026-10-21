import json
from pathlib import Path

import pytest

from samagents.config import load_settings
from samagents.db import Database

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = Path(__file__).parent / "fixtures"


@pytest.fixture
def settings(monkeypatch):
    for var in ("SAM_API_KEY", "ANTHROPIC_API_KEY", "EMAIL_FROM", "EMAIL_TO", "SMTP_HOST", "RESEND_API_KEY"):
        monkeypatch.delenv(var, raising=False)
    monkeypatch.setenv("SAM_API_KEY", "test-key")
    monkeypatch.setenv("EMAIL_FROM", "radar@example.com")
    monkeypatch.setenv("EMAIL_TO", "owner@example.com, partner@example.com")
    monkeypatch.setenv("SMTP_HOST", "smtp.example.com")
    monkeypatch.setenv("DATABASE_PATH", ":memory:")
    return load_settings(ROOT / "config.yaml", env_path=None)


@pytest.fixture
def db():
    d = Database(":memory:")
    yield d
    d.close()


@pytest.fixture
def search_payload():
    return json.loads((FIXTURES / "search_response.json").read_text())
