# SAM.gov RFP Radar

Agents that watch [SAM.gov](https://sam.gov) around the clock for federal
solicitations, RFQs, presolicitations and sources-sought notices that buy
**merchandise** (health and beauty aids, outdoor and tactical gear, clothing,
footwear, accessories, household goods and other products), skip construction
and labor, keep everything in a database, and email you a short report of what
each buyer wants.

```
          every N hours                               daily at 07:00
 SAM.gov ──► Discovery ──► Enrichment ──► Analyst ──► Reporter ──► your inbox
  API         agent          agent      (Claude)       agent
                │              │            │             │
                └──────────────┴─── SQLite ─┴─────────────┘
```

| Agent | What it does |
|---|---|
| **Discovery** | Pulls every notice posted in the last `lookback_days` from the official SAM.gov Opportunities API, normalizes it, and runs the product/service pre-filter. |
| **Enrichment** | Fetches the full notice text for candidates (official API, with sam.gov's key-free website endpoint as a fallback). |
| **Analyst** | Claude reads each candidate and returns a structured verdict: relevant or not, a 0-100 fit score, the category, a plain-English summary, the product list with quantities and specs, key requirements, delivery location and due date. |
| **Reporter** | Sends a daily HTML digest grouped by category (with a CSV attached), plus instant alerts for anything scoring 85+. |
| **Scheduler** | Runs the above 24/7 inside the daily SAM.gov request budget. |

## 1. Get the keys (no password sharing needed)

**SAM.gov API key.** Sign in at sam.gov, open *Account Details*, re-enter your
password and copy the *Public API Key*. That key is all the agents need; never
hand your SAM.gov password to anyone or any tool.

Quota matters: a personal key gets **10 requests/day** if your account has no
role in SAM.gov and **1,000 requests/day** once it holds a role on a registered
entity (for example Entity Administrator on your company's registration). Set
`sam.daily_request_budget` in `config.yaml` to the tier you have. The defaults
fit the 10/day tier: 4 discovery runs a day, one page each, plus a few text
fetches. With 1,000/day set `discovery_every_hours: 1` and
`enrich_max_per_run: 50`.

**Anthropic API key** for the analyst, from https://console.anthropic.com.

**Email.** Either SMTP (Gmail works with an App Password: `smtp.gmail.com`,
port 587) or a [Resend](https://resend.com) API key.

## 2. Configure

```bash
cd sam-rfp-agents
cp .env.example .env        # fill in the keys and email settings
```

`config.yaml` holds everything that is not a secret: notice types, schedule,
the product/service filter, categories and their PSC codes and keywords, the
analyst model and the business profile the analyst scores against. Edit the
`business_profile` paragraph to describe your lines; it is the single biggest
lever on fit scores.

## 3. Run

### Option A: Docker (any VPS, NAS or always-on PC)

```bash
docker compose up -d --build
docker compose logs -f
```

### Option B: plain Python + systemd

```bash
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/python -m samagents test-email     # confirm delivery works
.venv/bin/python -m samagents once           # first pass right now
.venv/bin/python -m samagents run            # 24/7 loop
```

`deploy/sam-rfp-radar.service` is a ready systemd unit.

### Option C: GitHub Actions (no server at all)

`.github/workflows/sam-rfp-radar.yml` runs a pass every 6 hours on GitHub's
schedulers and keeps the database on a `sam-rfp-radar-data` branch. Add the
repository secrets listed at the top of that file and enable the workflow.
The daily digest goes out on the first run after 07:00 in the configured
timezone.

## Commands

```
python -m samagents run                    24/7 scheduler
python -m samagents once [--report-if-due] one discover/enrich/analyze/alert pass
python -m samagents discover | enrich | analyze [--limit N]
python -m samagents report [--force] [--if-due] [--preview out.html]
python -m samagents alerts
python -m samagents status                 counts, budget left, last runs
python -m samagents show <notice_id>       one notice with its analysis
python -m samagents export --out file.csv [--min-score 60]
python -m samagents test-email
```

## What lands in the email

For every new notice that the analyst marks relevant and scores at or above
`analyst.min_fit_score_for_report` (default 40):

- title linked to the SAM.gov notice, agency, notice type, posted and **due** dates
- fit score, colored by band
- 2-3 sentence summary of who is buying what and why
- product table: item, quantity, specs
- key requirements: set-aside, Berry Amendment / TAA, brand-name-or-equal, samples
- NAICS, PSC, place of delivery, solicitation number, point of contact

Each notice is reported once. A CSV with the same rows is attached.

## How the product/service filter works

SAM's Product Service Code (PSC) is the strongest signal. Product codes start
with a digit (8415 clothing, 8465 individual equipment, 8530 toiletries, 7810
athletic equipment); service codes start with a letter (Y and Z construction,
R professional services, J maintenance, S housekeeping). The pre-filter keeps
numeric PSCs, drops lettered ones and construction/staffing NAICS codes, and
rescues notices with no PSC when the title plainly buys goods ("purchase of",
"uniforms", "backpacks"...). The analyst then makes the final call with the
full text. Tune the lists in `config.yaml`; nothing is hard-coded.

## Database

SQLite at `DATABASE_PATH` (default `data/opportunities.sqlite`):

- `opportunities`: every notice seen, raw JSON included, with the pre-filter verdict
- `analyses`: the analyst's structured verdict per notice
- `reports` / `report_items`: what was emailed and when
- `api_calls`: SAM.gov request log that enforces the daily budget
- `runs`: per-agent run history and stats

Open it with any SQLite browser, or `python -m samagents export`.

## Tests

```bash
pip install -r requirements-dev.txt
pytest
```

The tests use a recorded SAM.gov response and a fake analyst, so they run
without any keys or network.
