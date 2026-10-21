import pytest

from samagents.samgov import BudgetExhausted, SamGovClient, normalize, strip_html


class FakeResponse:
    def __init__(self, status_code, payload=None, text=""):
        self.status_code = status_code
        self._payload = payload
        self.text = text

    def json(self):
        if self._payload is None:
            raise ValueError("no json")
        return self._payload


class FakeSession:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []
        self.headers = {}

    def get(self, url, params=None, timeout=None, headers=None):
        self.calls.append((url, params))
        return self.responses.pop(0)


def test_strip_html():
    assert strip_html("<p>Hello&nbsp;<b>world</b></p><ul><li>one</li><li>two</li></ul>") == "Hello world\n- one\n- two"


def test_normalize_maps_fields(search_payload):
    row = normalize(search_payload["opportunitiesData"][0])
    assert row["notice_id"] == "a1b2c3d4e5f60718293a4b5c6d7e8f90"
    assert row["psc_code"] == "8415"
    assert row["naics_code"] == "315250"
    assert row["pop_city"] == "Fort Drum" and row["pop_state"] == "NY"
    assert row["description_url"].startswith("https://api.sam.gov")
    assert '"email": "buyer@army.mil"' in row["poc_json"]
    assert row["active"] == 1


def test_budget_blocks_requests(db, search_payload):
    session = FakeSession([FakeResponse(200, search_payload), FakeResponse(200, search_payload)])
    client = SamGovClient("key", db, daily_budget=1, session=session)
    from datetime import date
    client.search(date(2026, 10, 19), date(2026, 10, 21))
    assert client.remaining_budget() == 0
    with pytest.raises(BudgetExhausted):
        client.search(date(2026, 10, 19), date(2026, 10, 21))
    assert len(session.calls) == 1
    assert session.calls[0][1]["postedFrom"] == "10/19/2026"
    assert session.calls[0][1]["api_key"] == "key"


def test_429_counts_as_exhausted(db):
    session = FakeSession([FakeResponse(429, text="over quota")])
    client = SamGovClient("key", db, daily_budget=5, session=session)
    with pytest.raises(BudgetExhausted):
        client.description("abc")
    assert db.api_calls_last_24h() == 1


def test_site_description_swallows_failures(db):
    session = FakeSession([FakeResponse(403, text="blocked")])
    client = SamGovClient("key", db, daily_budget=5, session=session)
    assert client.site_description("abc") is None
    assert db.api_calls_last_24h() == 0  # key-free path never spends budget


def test_site_description_parses_body(db):
    session = FakeSession([FakeResponse(200, {"description": [{"body": "<p>Need <b>500</b> parkas</p>"}]})])
    client = SamGovClient("key", db, daily_budget=5, session=session)
    assert client.site_description("abc") == "Need 500 parkas"
