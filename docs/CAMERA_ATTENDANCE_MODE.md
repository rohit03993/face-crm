# Camera attendance mode (Mode B)

## App product flow

1. Home → **Students** → list + **Add student** (roll, name, 3–4 photos)
2. Home → **Ready for punch** (screen stays awake)
3. Person stands in oval → auto identify → CRM `/api/face-verify/camera-punch`

## Face API

- `GET /students` — list with `enrolled` + `image_count`
- `POST /students/{id}/enroll` — 3–6 images; creates student if missing
- `POST /camera-identify` — 1:N match → signed CRM camera punch

## Production Face API `.env`

```env
CRM_CALLBACK_URL=https://folksindia.org/api/face-verify/approve
CRM_CAMERA_PUNCH_URL=https://folksindia.org/api/face-verify/camera-punch
CAMERA_PUNCH_COOLDOWN_SECONDS=60
```

Restart the API container after changing `.env`.

RFID WebSocket verification remains available as an advanced Settings option.
