# Deployment

## Local (dev)

### 1. MySQL
```bash
docker compose up -d mysql
```

### 2. API
```bash
cd api
copy .env.example .env   # Windows
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
alembic upgrade head
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

First InsightFace call downloads `buffalo_l` models (~100MB+) into `~/.insightface`.

### 3. Seed + mock CRM
```bash
# terminal A
python scripts/mock_crm_server.py

# terminal B
python scripts/seed_demo.py
# enroll faces (Android or multipart), then:
python scripts/trigger_verification.py
```

### 4. Android
- Install `w600k_r50.onnx` into `android/app/src/main/assets/`
- Open `android/` in Android Studio, run on device
- Settings: API URL, device id/token from `scripts/demo_credentials.json`

## Docker (API + MySQL)
```bash
docker compose up --build
```
Run migrations once:
```bash
docker compose exec api alembic upgrade head
```

## Production notes
- Put API behind HTTPS / reverse proxy; terminate TLS there
- Rotate `CRM_SERVICE_TOKEN`, `DEVICE_TOKEN_PEPPER`, `CRM_CALLBACK_SECRET`
- Persist `api/storage` and MySQL volumes
- Prefer GPU ONNX provider on the server only if you add server-side match later (MVP is on-device)
- Kiosk: set as lock-task / device owner for true kiosk mode
- Network: kiosk must reach Face API; CRM must reach Face API; Face API must reach CRM callback URL

## E2E dry-run checklist
1. `/health` OK  
2. Device + student seeded  
3. Student enrolled (5–10 faces)  
4. Kiosk WS shows Connected  
5. Mock CRM listening  
6. `trigger_verification.py` → kiosk verifies → PASS → mock CRM prints callback  
7. Tune threshold with real faces  
