# Face Verify ↔ School CRM — Integration Guide

**Audience:** School CRM team (Laravel / Filament)  
**From:** Face Verify stack (`face-verify` monorepo)  
**Date:** July 2026  
**Status:** Face Verify MVP tested (enroll + live verify PASS). CRM integration pending.

---

## 1. Purpose of this document

This guide explains:

1. What the Face Verify team has already built and tested  
2. What the School CRM team must implement  
3. Exact API contracts, auth, and callback flow  
4. Production settings  
5. Current Android UI/UX status (MVP — functional, not production-polished)

**Rule (locked):** School CRM remains the **source of truth** for students and final attendance. Face Verify owns face AI + kiosk verification UX only.

---

## 2. What we built (Face Verify stack)

### Repositories / layout

```text
face-verify/
  api/        Python FastAPI — enroll, embeddings, WebSocket hub, CRM callback
  android/    Kotlin kiosk — camera, on-device ArcFace match, enrollment mode
  docs/       Contracts + this guide
```

### What works today (tested on real device)

| Capability | Status |
|------------|--------|
| Student upsert + face enrollment (5–10 images) | Done |
| Store ArcFace embeddings (`w600k_r50`, 512-d) | Done |
| RFID-style verification request → Android over WebSocket | Done |
| On-device face match + PASS/FAIL UI | Done |
| Result POST back to Face API | Done |
| On PASS → HMAC-signed callback to CRM URL | Done (tested with mock CRM) |
| Fail capture image saved for review | Done |
| Pending request timeout | Done |

### What Face Verify does **not** do

- Does not mark Present / IN / OUT  
- Does not send WhatsApp or update TV display  
- Does not replace ZKTeco / ADMS ingest  
- Does not own the student master data long-term (CRM does)

Those remain CRM responsibilities **after** a successful face PASS.

---

## 3. End-to-end flow (target production)

```text
Student taps RFID / biometric machine
        │
        ▼
School CRM (ADMS /iclock/*)  ← already exists
  • Resolve student by PIN = enrollment_number
  • DO NOT process attendance yet
  • POST Face API /verification-requests
        │
        ▼
Face Verify API
  • Load student embedding
  • Create PENDING request
  • Push over WebSocket to Android kiosk
        │
        ▼
Android Kiosk
  • Capture face → on-device ArcFace match
  • PASS / FAIL UI + sound
  • POST /verification-results
        │
        ▼
Face Verify API
  • On PASS → POST CRM /api/face-verify/approve (signed)
  • On FAIL / TIMEOUT → no attendance
        │
        ▼
School CRM
  • Write punch_logs
  • Run PunchAttendanceProcessor (WhatsApp, TV, IN/OUT)
```

### Demo identity used in MVP

| Field | Demo value |
|-------|------------|
| Enrollment / roll | `STU001` |
| Student name | Demo Student |
| Device name | Gate 1 Kiosk |

In production, enrollments come from real CRM students (`enrollment_number`).

---

## 4. What CRM must implement

### 4.1 Config / env (School CRM)

```env
FACE_VERIFY_ENABLED=true
FACE_VERIFY_API_URL=https://face-api.your-domain.com
FACE_VERIFY_SERVICE_TOKEN=<shared-secret-same-as-face-api>
FACE_VERIFY_CALLBACK_SECRET=<hmac-secret-same-as-face-api>
FACE_VERIFY_DEFAULT_DEVICE_ID=<uuid-of-gate-kiosk>
# Or map biometric device serial → face-verify device_id in DB

# Critical: for gated devices, do NOT inline-process attendance on ADMS punch
BIOMETRIC_ADMS_PROCESS_INLINE=false
```

Suggested mapping table (optional but recommended):

| biometric_devices.serial | face_verify_device_id | gate |
|--------------------------|------------------------|------|
| ZKTeco serial… | Face API device UUID | Main entry |

### 4.2 Change ADMS punch handling

**Today (typical):** ADMS punch → `biometric_punches` → `punch_logs` → `PunchAttendanceProcessor` immediately.

**Required for face-gated gates:**

1. Receive ADMS punch as today (`BiometricAdmsIngestService` / `/iclock/*`)  
2. Resolve student: device PIN = `enrollments.enrollment_number` (normalize uppercase/trim)  
3. If student not found → reject / log; no attendance  
4. If face verify enabled for this device:  
   - Create CRM pending record (optional but useful for admin UI)  
   - Call Face API `POST /verification-requests`  
   - **Do not** call `PunchAttendanceProcessor` yet  
5. If face verify disabled for device → keep old inline behavior  

**CRM → Face API request example:**

```http
POST {FACE_VERIFY_API_URL}/verification-requests
Authorization: Bearer {FACE_VERIFY_SERVICE_TOKEN}
Content-Type: application/json

{
  "enrollment_number": "STU001",
  "device_id": "1014e85f-df0e-44bf-9029-3e4710e0e268",
  "crm_request_id": "biometric_punch_id_or_uuid",
  "meta": {
    "source": "adms",
    "serial": "DEVICE_SERIAL",
    "user_pin": "STU001"
  }
}
```

Accept either `enrollment_number` or Face API `student_id`.

### 4.3 Approve callback endpoint (required)

```http
POST /api/face-verify/approve
```

**Headers from Face API:**

- `Authorization: Bearer {FACE_VERIFY_SERVICE_TOKEN}`  
- `X-Face-Verify-Signature: HMAC-SHA256(raw_body, FACE_VERIFY_CALLBACK_SECRET)`  
- `Content-Type: application/json`

**Body example:**

```json
{
  "request_id": "face-api-request-uuid",
  "crm_request_id": "biometric_punch_id_or_uuid",
  "student_id": "face-api-student-uuid",
  "enrollment_number": "STU001",
  "device_id": "face-api-device-uuid",
  "score": 0.51,
  "status": "PASS",
  "timestamp": "2026-07-17T00:00:00+00:00"
}
```

**CRM must:**

1. Verify bearer token **and/or** HMAC signature  
2. Accept only `status == "PASS"`  
3. Be **idempotent** on `request_id` / `crm_request_id` (duplicate callbacks safe)  
4. Write / finalize `punch_logs` (`employee_id` / PIN = enrollment_number)  
5. Run existing `PunchAttendanceProcessor` (WhatsApp, TV, IN/OUT)  
6. Return `200` with a simple JSON body, e.g. `{"ok": true}`

### 4.4 FAIL / TIMEOUT behavior

| Result | CRM action |
|--------|------------|
| PASS | Mark attendance via existing processor |
| FAIL | No attendance; optionally show in Filament review queue |
| TIMEOUT | No attendance; log for ops |

Face API stores fail images under its own storage; CRM can later link/review via API or admin tooling.

### 4.5 Student sync for enrollment

Before Android enrollment:

1. CRM (or admin job) upserts student into Face API:

```http
POST {FACE_VERIFY_API_URL}/students
Authorization: Bearer {FACE_VERIFY_SERVICE_TOKEN}

{
  "enrollment_number": "STU001",
  "name": "Student Name",
  "batch": "Class/Batch",
  "crm_student_id": "123"
}
```

2. Enrollment images are captured on Android kiosk (or admin enrollment mode) using **roll number** (`STU001`), not the long UUID.

### 4.6 Suggested CRM UI (Filament)

Minimum useful screens:

1. **Face Verify settings** — enable flag, API URL, tokens, default device  
2. **Pending verifications** — list PENDING / PASS / FAIL / TIMEOUT with score + timestamps  
3. **Manual approve** (optional) — admin override for FAIL after reviewing fail image  
4. **Enrollment helper** — button “Sync student to Face API” from student profile  

---

## 5. Face Verify API contract (summary)

Base URL (prod): `https://face-api.your-domain.com`  
Interactive docs (dev): `http://host:8000/docs`  
Full OpenAPI: `docs/openapi.yaml` / `docs/API_CONTRACT.md`

### Auth

| Caller | Auth |
|--------|------|
| CRM → Face API | `Authorization: Bearer {CRM_SERVICE_TOKEN}` |
| Android kiosk → Face API REST | `Authorization: Bearer {device_token}` |
| Android WebSocket | `WS /ws/kiosk/{device_id}?token={device_token}` |

### Key endpoints

| Method | Path | Who | Purpose |
|--------|------|-----|---------|
| GET | `/health` | Anyone | Liveness |
| POST | `/students` | CRM | Upsert student |
| POST | `/students/{id_or_roll}/enroll` | Kiosk/CRM | 5–10 face images → embedding |
| GET | `/students/{id}/embedding` | CRM/Kiosk | Fetch template |
| POST | `/devices` | CRM/Admin | Register kiosk + plain token (hashed at rest) |
| POST | `/verification-requests` | CRM | After RFID; pushes WS payload with embedding |
| POST | `/verification-results` | Kiosk | score + pass/fail + optional fail image |
| GET | `/verification-requests/{id}` | CRM | Poll status |
| WS | `/ws/kiosk/{device_id}` | Kiosk | Live verification push |

### WebSocket payload to kiosk

```json
{
  "type": "verification_request",
  "request_id": "...",
  "student_id": "...",
  "enrollment_number": "STU001",
  "name": "Demo Student",
  "model_version": "w600k_r50",
  "embedding": [0.01, 0.02],
  "threshold": 0.3,
  "timeout_seconds": 30
}
```

Matching is **on-device**. Embedding is included in the WS payload (no need for kiosk to pre-download all templates for MVP).

---

## 6. Production settings checklist

### Face API server

| Setting | Notes |
|---------|-------|
| HTTPS | Required |
| Strong `CRM_SERVICE_TOKEN` | Rotate; never use demo values |
| Strong `CRM_CALLBACK_SECRET` | Must match CRM |
| Strong `DEVICE_TOKEN_PEPPER` | Hash device tokens |
| MySQL backups | Include `face_templates` |
| Persist `storage/faces` + `storage/fails` | Audit + fail review |
| One device per physical gate | Unique token each |

### Android kiosk (per device)

| Setting | Production |
|---------|------------|
| API base URL | `https://face-api.your-domain.com` |
| Device ID | From Face API `/devices` |
| Device token | Issued once; store securely |
| Match threshold | Tuned on real faces (start ~0.30) |

### CRM

| Setting | Production |
|---------|------------|
| Face verify enabled per gate | Prefer per-device, not global only |
| Inline ADMS processing | Off for gated devices |
| Callback URL reachable from Face API | Firewall / VPC |

---

## 7. Android app — UI / UX status

A visual refresh shipped for the Android kiosk (July 2026):

- Full-bleed camera with face oval guide
- Brand status panel (Ready / Checking / Verified / Not matched)
- Online connection pill with live status
- Cleaner enroll sheet with progress bar
- Dark slate + teal gate aesthetic (readable at 1–2 meters)

Still optional before campus go-live: admin PIN for Settings, school logo, multi-language.

---

## 8. CRM reference files (existing School CRM)

These are **CRM-repo** paths for implementers:

| Area | Typical path |
|------|----------------|
| ADMS ingest | `app/Services/Biometric/BiometricAdmsIngestService.php` |
| ADMS routes | `routes/biometric.php` → `/iclock/*` |
| Punch processing | `app/Services/Punch/PunchAttendanceProcessor.php` |
| IN/OUT | `app/Services/Punch/PunchInOutCalculator.php` |
| Student identity | `enrollments.enrollment_number` = device PIN |
| Biometric config | `config/biometric.php` |
| Devices | `biometric_devices` |
| Live attendance UI | `app/Filament/Pages/AttendancePage.php` |
| TV display | reads `punch_logs` only |

---

## 9. Suggested CRM implementation order

1. Add Face Verify config + secrets  
2. Implement `POST /api/face-verify/approve` (HMAC + idempotent + punch processor)  
3. Gate ADMS inline processing for one test device  
4. Call Face API `/verification-requests` after RFID  
5. Sync one real student → enroll on kiosk → end-to-end RFID test  
6. Filament pending/fail review  
7. Roll out per gate  

---

## 10. Acceptance criteria (joint)

CRM + Face Verify integration is “done” when:

1. Real RFID tap creates Face API PENDING request  
2. Kiosk verifies same student’s face within timeout  
3. PASS → CRM attendance appears (punch_logs + WhatsApp/TV as today)  
4. FAIL → no attendance  
5. TIMEOUT → no attendance  
6. Duplicate PASS callback does not double-mark attendance  
7. Production secrets are not demo defaults  

---

## 11. Contacts / artifacts

| Artifact | Location |
|----------|----------|
| API contract | `docs/API_CONTRACT.md` |
| OpenAPI | `docs/openapi.yaml` |
| Deployment | `docs/DEPLOYMENT.md` |
| Embedding parity | `docs/EMBEDDING_PARITY.md` |
| Architecture handoff | `docs/RFID_AI_FACE_VERIFICATION_HANDOFF.md` |
| This CRM share pack | `docs/CRM_TEAM_SHARE_GUIDE.md` |

---

## 12. One-line summary for CRM leadership

> Face Verify is ready as a separate service that proves “RFID + AI face gate” on a real Android device. School CRM must stop marking attendance on RFID alone for gated devices, call Face Verify after each punch, and mark attendance only on the signed PASS callback — using the existing punch processor.
