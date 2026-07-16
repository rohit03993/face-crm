# Face Verify

RFID-gated AI face verification stack for School CRM attendance.

School CRM remains the source of truth for students and final attendance.
This monorepo owns face embeddings, kiosk UX, and the verification WebSocket hub.

## Layout

```text
face-verify/
  api/                 Python FastAPI (enroll, embeddings, WS hub, CRM callback)
  android/             Kotlin kiosk + enrollment mode (on-device ArcFace match)
  docs/                API contracts + CRM integration guide
  docker-compose.yml   Local API + MySQL
```

## Quick start (API)

```bash
cp api/.env.example api/.env
docker compose up -d mysql
cd api
python -m venv .venv
# Windows: .venv\Scripts\activate
pip install -r requirements.txt
alembic upgrade head
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

## Quick start (Android)

Open `android/` in Android Studio, set API URL + device token in Settings, run on a camera device.

## Docs

- [CRM team share guide](docs/CRM_TEAM_SHARE_GUIDE.md) — **share this with School CRM**
- [Handoff](docs/RFID_AI_FACE_VERIFICATION_HANDOFF.md)
- [API contract](docs/API_CONTRACT.md)
- [CRM integration (short)](docs/CRM_INTEGRATION.md)
- [Deployment](docs/DEPLOYMENT.md)
