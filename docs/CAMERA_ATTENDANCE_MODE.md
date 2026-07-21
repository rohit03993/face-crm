# Camera attendance mode (Mode B)

## App product flow

1. Home → **Students** → list + **Add student** (roll, name, 3–4 photos)
2. Home → **Ready for punch** (screen stays awake)
3. Person stands in oval, looks at camera, holds still ~1s → identify → CRM `/api/face-verify/camera-punch`

## Enrollment

- Add student: enter **roll + name first**, then **one frontal live capture** (eyes open, look straight, hold still)
- `POST /students/enroll-template` with `image_count=1`

## Face API

- `GET /students` — list with `enrolled` + `image_count`
- `POST /students/{id}/enroll` — 3–6 images; creates student if missing
- `POST /camera-identify` — 1:N match → signed CRM camera punch

## Production Face API `.env`

```env
PLATFORM_ADMIN_TOKEN=<vendor-secret>
APP_TIMEZONE=Asia/Kolkata
CAMERA_PUNCH_COOLDOWN_SECONDS=900
```

Add schools via `/platform/admin` (or `POST /platform/tenants`).  
Each school CRM connects with **Face URL + client code** (Setup → Face camera connect).  
APK uses **Face URL + device number + token**.

See [MULTI_TENANT_FACE_PLATFORM.md](MULTI_TENANT_FACE_PLATFORM.md).

Legacy single-school env URLs (`CRM_CALLBACK_URL` / `CRM_CAMERA_PUNCH_URL`) remain as fallback only.

