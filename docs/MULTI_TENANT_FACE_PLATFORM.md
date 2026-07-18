# Multi-tenant Face Platform (brief)

One Face website + one APK + many School CRMs.

## Ops flow

1. Open `https://face.taskbook.co.in/platform/admin` (or your Face host)
2. Enter platform admin token + school name + CRM URL (e.g. `https://paldigital.in`)
3. Copy **Client code**, **Device number**, **Device token**
4. In that school CRM → **Setup → Face camera connect** → Face URL + Client code → Connect
5. On APK Settings → same Face URL + Device number + Device token → Save

## APIs

| Method | Path | Auth | Purpose |
|--------|------|------|---------|
| POST | `/platform/tenants` | Platform admin | Add school client |
| GET | `/platform/tenants` | Platform admin | List clients |
| POST | `/platform/connect` | Client code in body | CRM connects and receives tokens |
| POST | `/platform/tenants/{id}/devices` | Platform admin | Add another kiosk |
| GET | `/platform/admin` | — | Simple HTML to add a client |

## Env (Face Platform once)

```env
PLATFORM_ADMIN_TOKEN=...   # or falls back to CRM_SERVICE_TOKEN
APP_TIMEZONE=Asia/Kolkata
```

Per-school CRM URLs live in the `tenants` table — not in `.env`.

## Migrate

```bash
cd api
alembic upgrade head
```
