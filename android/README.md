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
Open **Settings**:
- API base URL (`https://face.folksindia.org` on production)
- Device ID (from `POST /devices`)
- Device token

## Modes
- **Ready for punch:** continuous identify → `POST /camera-identify` → CRM punch  
- **Students / Add:** enroll 3–6 face photos (recommended 3–4)  
- **RFID verify (Settings advanced):** legacy WebSocket card-gate screen  

## Kiosk hardening
- `BOOT_COMPLETED` opens Home
- Attendance screen uses `keepScreenOn`
- WS reconnect / offline queue remain on RFID screen
