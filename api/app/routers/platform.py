"""Vendor platform: add school clients and manage devices."""

from __future__ import annotations

import secrets
import string
from pathlib import Path
from urllib.parse import urlparse

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import HTMLResponse
from sqlalchemy.orm import Session

from app.auth import hash_token, require_platform_admin
from app.db import get_db
from app.models import Device, Tenant, generate_client_code, generate_secret
from app.schemas import (
    DeviceTokenOut,
    DeviceUpdateIn,
    TenantAddDeviceIn,
    TenantConnectIn,
    TenantConnectOut,
    TenantCreateIn,
    TenantCreateOut,
    TenantDetailOut,
    TenantDeviceOut,
    TenantListItem,
    TenantUpdateIn,
)

router = APIRouter(prefix="/platform", tags=["platform"])


def _normalize_crm_base(url: str) -> str:
    raw = url.strip().rstrip("/")
    if not raw.startswith("http://") and not raw.startswith("https://"):
        raw = "https://" + raw
    parsed = urlparse(raw)
    if not parsed.netloc:
        raise HTTPException(status_code=422, detail="Invalid CRM website URL")
    return f"{parsed.scheme}://{parsed.netloc}"


def _next_device_id(db: Session) -> str:
    existing = [d.id for d in db.query(Device.id).all() if d.id and str(d.id).isdigit()]
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
    return "".join(secrets.choice(string.digits) for _ in range(8))


def _device_out(device: Device, token: str | None = None) -> TenantDeviceOut:
    return TenantDeviceOut(
        id=device.id,
        name=device.name,
        token=token,
        gate=device.gate,
        is_active=device.is_active,
        tenant_id=device.tenant_id,
        created_at=device.created_at,
    )


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
    existing = (
        db.query(Tenant)
        .filter(Tenant.crm_base_url == crm_base)
        .first()
    )
    if existing:
        raise HTTPException(
            status_code=409,
            detail=(
                f"A client already exists for {crm_base} "
                f"(code {existing.client_code}). Open it below instead of adding again."
            ),
        )

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
        devices_out.append(_device_out(device, plain))

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


@router.get(
    "/tenants/{tenant_id}",
    response_model=TenantDetailOut,
    dependencies=[Depends(require_platform_admin)],
)
def get_tenant(tenant_id: str, db: Session = Depends(get_db)) -> TenantDetailOut:
    tenant = db.get(Tenant, tenant_id)
    if not tenant:
        raise HTTPException(status_code=404, detail="Tenant not found")
    devices = (
        db.query(Device)
        .filter(Device.tenant_id == tenant.id)
        .order_by(Device.created_at.desc())
        .all()
    )
    return TenantDetailOut(
        id=tenant.id,
        name=tenant.name,
        client_code=tenant.client_code,
        crm_base_url=tenant.crm_base_url,
        timezone=tenant.timezone,
        is_active=tenant.is_active,
        created_at=tenant.created_at,
        devices=[_device_out(d) for d in devices],
    )


@router.patch(
    "/tenants/{tenant_id}",
    response_model=TenantDetailOut,
    dependencies=[Depends(require_platform_admin)],
)
def update_tenant(
    tenant_id: str,
    body: TenantUpdateIn,
    db: Session = Depends(get_db),
) -> TenantDetailOut:
    tenant = db.get(Tenant, tenant_id)
    if not tenant:
        raise HTTPException(status_code=404, detail="Tenant not found")

    if body.name is not None:
        tenant.name = body.name.strip()
    if body.crm_base_url is not None:
        tenant.crm_base_url = _normalize_crm_base(body.crm_base_url)
    if body.timezone is not None and body.timezone.strip():
        tenant.timezone = body.timezone.strip()
    if body.is_active is not None:
        tenant.is_active = 1 if body.is_active else 0

    db.commit()
    return get_tenant(tenant_id, db)


@router.get(
    "/tenants/{tenant_id}/devices",
    response_model=list[TenantDeviceOut],
    dependencies=[Depends(require_platform_admin)],
)
def list_tenant_devices(tenant_id: str, db: Session = Depends(get_db)) -> list[TenantDeviceOut]:
    tenant = db.get(Tenant, tenant_id)
    if not tenant:
        raise HTTPException(status_code=404, detail="Tenant not found")
    devices = (
        db.query(Device)
        .filter(Device.tenant_id == tenant.id)
        .order_by(Device.created_at.desc())
        .all()
    )
    return [_device_out(d) for d in devices]


@router.post("/connect", response_model=TenantConnectOut)
def connect_crm(body: TenantConnectIn, db: Session = Depends(get_db)) -> TenantConnectOut:
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
    return TenantConnectOut(
        tenant_id=tenant.id,
        name=tenant.name,
        client_code=tenant.client_code,
        crm_base_url=tenant.crm_base_url,
        service_token=tenant.service_token,
        callback_secret=tenant.callback_secret,
        timezone=tenant.timezone,
        devices=[_device_out(d) for d in devices],
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
    if not tenant:
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
    return _device_out(device, plain)


@router.patch(
    "/devices/{device_id}",
    response_model=TenantDeviceOut,
    dependencies=[Depends(require_platform_admin)],
)
def update_device(
    device_id: str,
    body: DeviceUpdateIn,
    db: Session = Depends(get_db),
) -> TenantDeviceOut:
    device = db.get(Device, device_id)
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")

    if body.name is not None:
        device.name = body.name.strip()
    if body.gate is not None:
        device.gate = body.gate.strip() or None
    if body.is_active is not None:
        device.is_active = 1 if body.is_active else 0

    db.commit()
    db.refresh(device)
    return _device_out(device)


@router.post(
    "/devices/{device_id}/regenerate-token",
    response_model=DeviceTokenOut,
    dependencies=[Depends(require_platform_admin)],
)
def regenerate_device_token(device_id: str, db: Session = Depends(get_db)) -> DeviceTokenOut:
    device = db.get(Device, device_id)
    if not device:
        raise HTTPException(status_code=404, detail="Device not found")

    plain = _kiosk_device_token()
    for _ in range(10):
        if not db.query(Device).filter(Device.token_hash == hash_token(plain)).first():
            break
        plain = _kiosk_device_token()
    else:
        raise HTTPException(status_code=500, detail="Could not allocate device token")

    device.token_hash = hash_token(plain)
    device.is_active = 1
    db.commit()

    return DeviceTokenOut(id=device.id, name=device.name, token=plain)


@router.get("/admin", response_class=HTMLResponse, include_in_schema=False)
def admin_page() -> HTMLResponse:
    path = Path(__file__).resolve().parent.parent / "static" / "platform_admin.html"
    if not path.is_file():
        raise HTTPException(status_code=500, detail="Admin UI file missing")
    return HTMLResponse(path.read_text(encoding="utf-8"))
