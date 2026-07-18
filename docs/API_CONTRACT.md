# API Contract — Face Verify

Base URL: `http://<host>:8000`  
Auth:
- **CRM / admin:** `Authorization: Bearer <CRM_SERVICE_TOKEN>`
- **Kiosk:** `Authorization: Bearer <device_token>` (WS also accepts `?token=`)

---

## REST

### `GET /health`
```json
{
  "status": "ok",
  "app": "face-verify-api",
  "model_version": "w600k_r50",
  "embedding_dim": 512,
  "threshold": 0.4
}
```

### `POST /students` (CRM)
```json
{
  "enrollment_number": "STU001",
  "name": "Demo Student",
  "batch": "MVP-Batch-1",
  "crm_student_id": "1"
}
```

### `POST /students/{id}/enroll` (multipart)
- `images`: 5–10 image files
- `angles` (optional): comma-separated labels  
Returns `{ student_id, model_version, image_count, embedding_dim }`

### `GET /students/{id}/embedding`
Returns `{ student_id, enrollment_number, model_version, embedding: float[512], image_count }`

### `POST /devices` (CRM)
```json
{ "name": "Gate 1", "gate": "main", "token": "plain-device-token" }
```
Token is hashed at rest; return value does **not** include the raw token.

### `POST /verification-requests` (CRM — after RFID)
```json
{
  "student_id": "<uuid>",
  "device_id": "<uuid>",
  "crm_request_id": "optional-crm-id",
  "meta": { "source": "adms" }
}
```
Also accepts `enrollment_number` instead of `student_id`.  
Creates `PENDING`, pushes WS payload to kiosk (embedding included).

### `POST /verification-results` (kiosk — multipart)
- `request_id`, `score`, `passed` (`true`/`false`)
- `fail_image` (optional JPEG on fail)
- `note` (optional)

On `passed=true`, API calls CRM approve callback.

### `GET /verification-requests/{id}` (CRM)
Poll status: `PENDING | PASS | FAIL | TIMEOUT`

---

## WebSocket

`WS /ws/kiosk/{device_id}?token=<device_token>`

### Server → kiosk (verification)
```json
{
  "type": "verification_request",
  "request_id": "...",
  "student_id": "...",
  "enrollment_number": "STU001",
  "name": "Demo Student",
  "model_version": "w600k_r50",
  "embedding": [0.01, 0.02],
  "threshold": 0.4,
  "timeout_seconds": 30
}
```

### Server → kiosk (hello)
```json
{ "type": "connected", "device_id": "..." }
```

Client may send `ping`; server replies `pong`.

---

## CRM callback (Face API → CRM)

`POST {CRM_CALLBACK_URL}`  
Headers:
- `Authorization: Bearer <CRM_SERVICE_TOKEN>`
- `X-Face-Verify-Signature: HMAC-SHA256(body, CRM_CALLBACK_SECRET)`
- `Content-Type: application/json`

Body:
```json
{
  "request_id": "...",
  "crm_request_id": "...",
  "student_id": "...",
  "enrollment_number": "STU001",
  "device_id": "...",
  "score": 0.51,
  "status": "PASS",
  "timestamp": "2026-07-17T05:30:00+05:30"
}
```
