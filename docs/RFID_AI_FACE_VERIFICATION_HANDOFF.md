# RFID + AI Face Verification — Full Handoff Plan
**Audience:** Product owner + AI/dev agents building the **separate** Face Verify stack
**Related CRM:** School CRM (`school-crm` — Laravel/Filament)
**Date:** July 2026
**Status:** Architecture approved — build as **separate projects**, integrate via APIs

## Objective
Prevent proxy attendance while keeping existing RFID / biometric machines.
Attendance is marked in CRM **only after** successful AI face verification.

## Locked decisions
- Separate monorepo (Python + Android), not inside School CRM
- Attendance authority: School CRM
- Face matching: ArcFace embeddings (InsightFace / ONNX)
- MVP: on-device match, WebSocket hub in Python API, embedding in WS payload
- Enrollment: Android enrollment mode

## Architecture
RFID → CRM (ADMS) → Face API (pending request + WS push) → Android kiosk (on-device match) → Face API (result) → CRM (approve → punch_logs)

See also: [API_CONTRACT.md](API_CONTRACT.md), [CRM_INTEGRATION.md](CRM_INTEGRATION.md)
