# Face Verify Kiosk (Android)

## App flow

1. **Home** — Ready for punch · Students · Settings  
2. **Students** — list enrolled faces · Add student  
3. **Add student** — roll + name · capture 3–4 photos · save  
4. **Ready for punch** — screen stays on · auto face → CRM attendance  

## Requirements
- Android Studio Ladybug+ / AGP 8.7
- Device/emulator with camera (minSdk 26)
- ONNX model asset: `app/src/main/assets/w600k_r50.onnx`

## Model install
1. Download InsightFace `buffalo_l` pack.
2. Copy `w600k_r50.onnx` into `android/app/src/main/assets/`.
3. Rebuild the app.

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
