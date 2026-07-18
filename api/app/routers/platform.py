"""Vendor platform: add school clients and connect CRMs."""

from __future__ import annotations

import secrets
import string
from urllib.parse import urlparse

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import HTMLResponse
from sqlalchemy.orm import Session

from app.auth import hash_token, require_platform_admin
from app.db import get_db
from app.models import Device, Tenant, generate_client_code, generate_secret
from app.schemas import (
    TenantAddDeviceIn,
    TenantConnectIn,
    TenantConnectOut,
    TenantCreateIn,
    TenantCreateOut,
    TenantDeviceOut,
    TenantListItem,
)

router = APIRouter(prefix="/platform", tags=["platform"])


def _normalize_crm_base(url: str) -> str:
    raw = url.strip().rstrip("/")
    if not raw.startswith("http://") and not raw.startswith("https://"):
        raw = "https://" + raw
    parsed = urlparse(raw)
    if not parsed.netloc:
        raise HTTPException(status_code=422, detail="Invalid CRM website URL")
    # Store origin only (scheme + host[:port])
    return f"{parsed.scheme}://{parsed.netloc}"


def _next_device_id(db: Session) -> str:
    """Allocate a short numeric device number like 1001, 1002, …"""
    existing = [d.id for d in db.query(Device.id).all() if d.id and d.id.isdigit()]
    if not existing:
        return "1001"
    return str(max(int(x) for x in existing) + 1)


def _unique_client_code(db: Session) -> str:
    for _ in range(20):
        code = generate_client_code()
        if not db.query(Tenant).filter(Tenant.client_code == code).first():
            return code
    raise HTTPException(status_code=500, detail="Could not allocate client code")


def _kiosk_device_token() -> str:
    """Digits-only so kiosk keyboards (and older APKs) can type the token easily."""
    return "".join(secrets.choice(string.digits) for _ in range(8))


def _create_device_for_tenant(
    db: Session,
    tenant: Tenant,
    *,
    name: str,
    device_id: str | None,
    token: str | None,
    gate: str | None = None,
) -> tuple[Device, str]:
    plain = (token or "").strip() or _kiosk_device_token()

    token_hash = hash_token(plain)
    if db.query(Device).filter(Device.token_hash == token_hash).first():
        raise HTTPException(status_code=400, detail="Device token already registered")

    did = (device_id or "").strip() or _next_device_id(db)
    if db.get(Device, did):
        raise HTTPException(status_code=400, detail=f"Device number {did} already exists")

    device = Device(
        id=did,
        tenant_id=tenant.id,
        name=name,
        gate=gate,
        token_hash=token_hash,
    )
    db.add(device)
    return device, plain


@router.post(
    "/tenants",
    response_model=TenantCreateOut,
    dependencies=[Depends(require_platform_admin)],
)
def create_tenant(body: TenantCreateIn, db: Session = Depends(get_db)) -> TenantCreateOut:
    crm_base = _normalize_crm_base(body.crm_base_url)
    tenant = Tenant(
        name=body.name.strip(),
        client_code=_unique_client_code(db),
        crm_base_url=crm_base,
        service_token=generate_secret(),
        callback_secret=generate_secret(),
        timezone=(body.timezone or "Asia/Kolkata").strip() or "Asia/Kolkata",
        is_active=1,
    )
    db.add(tenant)
    db.flush()

    devices_out: list[TenantDeviceOut] = []
    if body.create_device:
        device, plain = _create_device_for_tenant(
            db,
            tenant,
            name=body.device_name.strip() or "Gate 1",
            device_id=body.device_id,
            token=body.device_token,
        )
        devices_out.append(TenantDeviceOut(id=device.id, name=device.name, token=plain))

    db.commit()
    db.refresh(tenant)

    return TenantCreateOut(
        id=tenant.id,
        name=tenant.name,
        client_code=tenant.client_code,
        crm_base_url=tenant.crm_base_url,
        service_token=tenant.service_token,
        callback_secret=tenant.callback_secret,
        timezone=tenant.timezone,
        devices=devices_out,
    )


@router.get(
    "/tenants",
    response_model=list[TenantListItem],
    dependencies=[Depends(require_platform_admin)],
)
def list_tenants(db: Session = Depends(get_db)) -> list[TenantListItem]:
    rows = db.query(Tenant).order_by(Tenant.created_at.desc()).all()
    out: list[TenantListItem] = []
    for t in rows:
        count = db.query(Device).filter(Device.tenant_id == t.id).count()
        out.append(
            TenantListItem(
                id=t.id,
                name=t.name,
                client_code=t.client_code,
                crm_base_url=t.crm_base_url,
                timezone=t.timezone,
                is_active=t.is_active,
                device_count=count,
            )
        )
    return out


@router.post("/connect", response_model=TenantConnectOut)
def connect_crm(body: TenantConnectIn, db: Session = Depends(get_db)) -> TenantConnectOut:
    """School CRM: paste client code + confirm CRM URL → get tokens for .env/settings."""
    code = body.client_code.strip().upper()
    tenant = (
        db.query(Tenant)
        .filter(Tenant.client_code == code, Tenant.is_active == 1)
        .first()
    )
    if not tenant:
        raise HTTPException(status_code=404, detail="Invalid client code")

    crm_base = _normalize_crm_base(body.crm_base_url)
    tenant.crm_base_url = crm_base
    db.commit()
    db.refresh(tenant)

    devices = db.query(Device).filter(Device.tenant_id == tenant.id, Device.is_active == 1).all()
    # Tokens are hashed — CRM only gets device numbers for display; plain token shown at create time.
    device_out = [TenantDeviceOut(id=d.id, name=d.name, token=None) for d in devices]

    return TenantConnectOut(
        tenant_id=tenant.id,
        name=tenant.name,
        client_code=tenant.client_code,
        crm_base_url=tenant.crm_base_url,
        service_token=tenant.service_token,
        callback_secret=tenant.callback_secret,
        timezone=tenant.timezone,
        devices=device_out,
    )


@router.post(
    "/tenants/{tenant_id}/devices",
    response_model=TenantDeviceOut,
    dependencies=[Depends(require_platform_admin)],
)
def add_device(
    tenant_id: str,
    body: TenantAddDeviceIn,
    db: Session = Depends(get_db),
) -> TenantDeviceOut:
    tenant = db.get(Tenant, tenant_id)
    if not tenant or not tenant.is_active:
        raise HTTPException(status_code=404, detail="Tenant not found")

    device, plain = _create_device_for_tenant(
        db,
        tenant,
        name=body.name.strip() or "Gate",
        device_id=body.device_id,
        token=body.token,
        gate=body.gate,
    )
    db.commit()
    return TenantDeviceOut(id=device.id, name=device.name, token=plain)


@router.get("/admin", response_class=HTMLResponse, include_in_schema=False)
def admin_page() -> str:
    """Simple vendor UI to add a school client (use with platform admin token)."""
    return """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <title>Face Platform — Add client</title>
  <style>
    :root { font-family: system-ui, sans-serif; color: #0f172a; }
    body { max-width: 720px; margin: 2rem auto; padding: 0 1rem; background: #f8fafc; }
    h1 { font-size: 1.4rem; }
    label { display: block; font-size: .85rem; font-weight: 600; margin-top: 1rem; }
    input { width: 100%; padding: .6rem .7rem; border: 1px solid #cbd5e1; border-radius: 8px; box-sizing: border-box; }
    button { margin-top: 1.25rem; background: #0f172a; color: #fff; border: 0; padding: .7rem 1.1rem; border-radius: 8px; font-weight: 600; cursor: pointer; }
    .card { background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; padding: 1.25rem; margin-top: 1rem; }
    pre { background: #0f172a; color: #e2e8f0; padding: 1rem; border-radius: 8px; overflow: auto; font-size: .8rem; }
    .muted { color: #64748b; font-size: .85rem; }
    .err { color: #b91c1c; }
  </style>
</head>
<body>
  <h1>Face Platform — Add school client</h1>
  <p class="muted">Creates a client code + device number. Paste the code into the school CRM. Enter device number + token in the APK Settings (Face URL = this site).</p>
  <div class="card">
    <label>Platform admin token</label>
    <input id="token" type="password" placeholder="PLATFORM_ADMIN_TOKEN"/>
    <label>School name</label>
    <input id="name" placeholder="Pal Digital"/>
    <label>CRM website</label>
    <input id="crm" placeholder="https://paldigital.in"/>
    <label>First device number (optional)</label>
    <input id="device_id" placeholder="3001"/>
    <button id="go">Add client</button>
    <p id="msg" class="muted"></p>
    <pre id="out" hidden></pre>
  </div>
  <script>
    document.getElementById('go').onclick = async () => {
      const msg = document.getElementById('msg');
      const out = document.getElementById('out');
      msg.textContent = 'Working…';
      msg.className = 'muted';
      out.hidden = true;
      try {
        const res = await fetch('/platform/tenants', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Authorization': 'Bearer ' + document.getElementById('token').value.trim(),
          },
          body: JSON.stringify({
            name: document.getElementById('name').value.trim(),
            crm_base_url: document.getElementById('crm').value.trim(),
            create_device: true,
            device_id: document.getElementById('device_id').value.trim() || null,
            device_name: 'Gate 1',
          }),
        });
        const data = await res.json();
        if (!res.ok) throw new Error(data.detail || JSON.stringify(data));
        const d = (data.devices && data.devices[0]) || {};
        out.textContent =
          'Give to school CRM:\\n' +
          '  Face URL:     ' + location.origin + '\\n' +
          '  Client code:  ' + data.client_code + '\\n\\n' +
          'Give to APK Settings:\\n' +
          '  Face URL:     ' + location.origin + '\\n' +
          '  Device no:    ' + (d.id || '') + '\\n' +
          '  Device token: ' + (d.token || '') + '\\n\\n' +
          'Also store in CRM (auto on Connect):\\n' +
          '  service_token / callback_secret returned by Connect API\\n\\n' +
          JSON.stringify(data, null, 2);
        out.hidden = false;
        msg.textContent = 'Client created. Copy the codes below.';
      } catch (e) {
        msg.className = 'err';
        msg.textContent = String(e.message || e);
      }
    };
  </script>
</body>
</html>
"""
