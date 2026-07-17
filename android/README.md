# Face Verify Kiosk (Android)

## App flow

1. **Home** — Ready for punch · Students · Settings  
2. **Students** — list enrolled faces · Add student  
3. **Add student** — roll + name · capture 3–4 photos · save  
4. **Ready for punch** — screen stays on · auto face → CRM attendance  

## Requirements
- Android Studio Ladybug+ / AGP 8.7
- Device with camera (minSdk 26) — **arm64-v8a** (APK is arm64-only for size)
- Face API serving the ONNX model at `/models/w600k_r50.onnx`

## Light APK (model not inside the APK)
The ArcFace file is **~166 MB** and is downloaded once on first Home open from:

`{API base URL}/models/w600k_r50.onnx`

On the server, place the file at `api/storage/models/w600k_r50.onnx`, then restart the API.

Build release APK:

```bash
cd android
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release-unsigned.apk  (typically ~25–40 MB)
```

## Configure
Open **Settings** (defaults already set):
- API base URL: `https://face.folksindia.org`
- Device ID: `1001`
- Device token: `48291573`

Create the matching device on Face API once:

```bash
curl -sS -X POST "https://face.folksindia.org/devices" \
  -H "Authorization: Bearer YOUR_CRM_SERVICE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"id":"1001","name":"Main Gate Kiosk","gate":"main","token":"48291573"}'
```

Then set CRM `FACE_VERIFY_DEFAULT_DEVICE_ID=1001` (and biometric `face_verify_device_id` if using RFID gate mode).

## Modes
- **Ready for punch:** continuous identify → `POST /camera-identify` → CRM punch  
- **Students / Add:** enroll 3–6 face photos (recommended 3–4)  
- **RFID verify (Settings advanced):** legacy WebSocket card-gate screen  

## Kiosk hardening
- `BOOT_COMPLETED` opens Home
- Attendance screen uses `keepScreenOn`
- WS reconnect / offline queue remain on RFID screen
