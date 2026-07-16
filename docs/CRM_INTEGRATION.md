# When we need CRM help

These changes belong in the **School CRM** (`school-crm`) Laravel repo — not in this Face Verify monorepo.

## Goal
RFID punch must **not** mark attendance immediately. CRM creates a pending verification, Face Verify kiosk confirms the face, then CRM writes `punch_logs` and runs the existing processor.

## Required CRM work

### 1. Gate ADMS inline processing
Today `BIOMETRIC_ADMS_PROCESS_INLINE` often mirrors punches straight into attendance.

For face-verify-enabled devices:
1. Receive ADMS punch as today (`BiometricAdmsIngestService`).
2. Resolve student via `enrollment_number` = device PIN.
3. **Do not** call `PunchAttendanceProcessor` yet.
4. Call Face API:

```http
POST {FACE_API}/verification-requests
Authorization: Bearer {CRM_SERVICE_TOKEN}
Content-Type: application/json

{
  "enrollment_number": "STU001",
  "device_id": "<face-verify-device-uuid>",
  "crm_request_id": "<biometric_punch_id>",
  "meta": { "serial": "...", "user_pin": "..." }
}
```

5. Store CRM-side pending row if useful (optional); Face API is source for verification status.

### 2. Approve callback endpoint
Add:

```text
POST /api/face-verify/approve
```

Verify:
- Bearer CRM service token **or** HMAC `X-Face-Verify-Signature`
- `status == PASS`

Then:
1. Write / finalize `punch_logs` (employee_id = enrollment_number / user_pin).
2. Run existing `PunchAttendanceProcessor` (WhatsApp, TV, IN/OUT).
3. Idempotent on `request_id` / `crm_request_id`.

### 3. Student sync / enroll identity
Face API needs student records before enrollment:
- On enroll UI open, CRM (or admin) `POST /students` with `enrollment_number`, `name`, `batch`, `crm_student_id`.
- Enrollment images are captured by Android and sent to Face API.

### 4. Config
Suggested env in CRM:
```env
FACE_VERIFY_ENABLED=true
FACE_VERIFY_API_URL=http://face-api:8000
FACE_VERIFY_SERVICE_TOKEN=...
FACE_VERIFY_CALLBACK_SECRET=...
FACE_VERIFY_DEVICE_ID=<uuid for gate kiosk>
BIOMETRIC_ADMS_PROCESS_INLINE=false   # or per-device flag
```

### 5. FAIL / TIMEOUT behavior
- No attendance.
- Optional Filament page to review `fail_captures` (Face API) and manually approve.

### 6. Files to touch (CRM reference)
| Area | Path |
|------|------|
| ADMS ingest | `app/Services/Biometric/BiometricAdmsIngestService.php` |
| Punch processor | `app/Services/Punch/PunchAttendanceProcessor.php` |
| Config | `config/biometric.php` |
| New API route | `routes/api.php` → Face Verify approve |

## Out of scope for CRM
- InsightFace / ONNX / Kotlin camera code
- Embedding storage
- WebSocket hub to kiosks
