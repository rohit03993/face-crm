# Face Verify Kiosk (Android)

## Requirements
- Android Studio Ladybug+ / AGP 8.7
- Device/emulator with camera (minSdk 26)
- ONNX model asset: `app/src/main/assets/w600k_r50.onnx`

## Model install
1. Download InsightFace `buffalo_l` pack.
2. Copy `w600k_r50.onnx` into `android/app/src/main/assets/`.
3. Rebuild the app.

## Configure
Open **Settings** in the app:
- API base URL (emulator → `http://10.0.2.2:8000`, real device → your LAN IP)
- Device ID (from `POST /devices` / seed script)
- Device token
- Match threshold (default `0.40`)

## Modes
- **Kiosk:** waits for WS verification requests, on-device ArcFace match, posts results
- **Enroll:** capture 5–10 angles, upload to `POST /students/{id}/enroll`

## Kiosk hardening
- `BOOT_COMPLETED` auto-launch
- Optional lock-task (`startLockTask`) — whitelist app as device owner/lock-task package for full kiosk
- WS reconnect with exponential backoff
- Offline result queue retry
