# Face Verify API

Python FastAPI service: InsightFace enrollment, embedding storage, WebSocket hub, CRM callback.

## Setup

```bash
copy .env.example .env
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
```

Ensure MySQL is up (`docker compose up -d mysql` from repo root), then:

```bash
alembic upgrade head
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

## Scripts
| Script | Purpose |
|--------|---------|
| `scripts/mock_crm_server.py` | Fake CRM approve endpoint on :9999 |
| `scripts/seed_demo.py` | Create demo device + student |
| `scripts/trigger_verification.py` | Simulate RFID → verification request |
| `scripts/embed_image.py` | Print server embedding for parity tests |

Interactive docs: http://127.0.0.1:8000/docs
