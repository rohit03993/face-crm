import hashlib
import hmac
import secrets

from fastapi import Depends, HTTPException, Security, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.orm import Session

from app.config import get_settings
from app.db import get_db
from app.models import Device, Tenant

bearer = HTTPBearer(auto_error=False)
settings = get_settings()


def hash_token(raw: str) -> str:
    peppered = f"{settings.device_token_pepper}:{raw}".encode()
    return hashlib.sha256(peppered).hexdigest()


def generate_token() -> str:
    return secrets.token_urlsafe(32)


def _platform_admin_token() -> str:
    return (settings.platform_admin_token or settings.crm_service_token or "").strip()


def require_platform_admin(
    credentials: HTTPAuthorizationCredentials | None = Security(bearer),
) -> None:
    expected = _platform_admin_token()
    if not expected:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Platform admin token not configured")
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing bearer token")
    if not hmac.compare_digest(credentials.credentials, expected):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid platform admin token")


def require_crm_token(
    credentials: HTTPAuthorizationCredentials | None = Security(bearer),
    db: Session = Depends(get_db),
) -> Tenant | None:
    """Accept platform admin or any active school service token."""
    return resolve_tenant_from_bearer(credentials, db)


def resolve_tenant_from_bearer(
    credentials: HTTPAuthorizationCredentials | None,
    db: Session,
) -> Tenant | None:
    """Return tenant for a school CRM service token, or None if platform admin."""
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing bearer token")

    raw = credentials.credentials
    if hmac.compare_digest(raw, _platform_admin_token()):
        return None

    tenant = (
        db.query(Tenant)
        .filter(Tenant.service_token == raw, Tenant.is_active == 1)
        .first()
    )
    if tenant:
        return tenant

    # Legacy single-token installs before multi-tenant connect.
    if hmac.compare_digest(raw, settings.crm_service_token):
        return db.query(Tenant).filter(Tenant.is_active == 1).order_by(Tenant.created_at.asc()).first()

    raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid CRM token")


def require_tenant_crm(
    credentials: HTTPAuthorizationCredentials | None = Security(bearer),
    db: Session = Depends(get_db),
) -> Tenant:
    """School CRM calls must use that school's service_token."""
    tenant = resolve_tenant_from_bearer(credentials, db)
    if tenant is None:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Use the school client service token, not the platform admin token.",
        )
    return tenant


def require_tenant_or_platform(
    credentials: HTTPAuthorizationCredentials | None = Security(bearer),
    db: Session = Depends(get_db),
) -> Tenant | None:
    """Platform admin → None; school CRM token → Tenant."""
    return resolve_tenant_from_bearer(credentials, db)


def require_device(
    credentials: HTTPAuthorizationCredentials | None = Security(bearer),
    db: Session = Depends(get_db),
) -> Device:
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing bearer token")
    token_hash = hash_token(credentials.credentials)
    device = (
        db.query(Device)
        .filter(Device.token_hash == token_hash, Device.is_active == 1)
        .first()
    )
    if not device:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid device token")
    return device


def require_crm_or_device(
    credentials: HTTPAuthorizationCredentials | None = Security(bearer),
    db: Session = Depends(get_db),
) -> tuple[str, Device | None, Tenant | None]:
    """Returns ('crm', None, tenant|None) or ('device', Device, tenant)."""
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing bearer token")

    raw = credentials.credentials
    if hmac.compare_digest(raw, _platform_admin_token()) or hmac.compare_digest(
        raw, settings.crm_service_token
    ):
        tenant = resolve_tenant_from_bearer(credentials, db)
        return ("crm", None, tenant)

    token_hash = hash_token(raw)
    device = (
        db.query(Device)
        .filter(Device.token_hash == token_hash, Device.is_active == 1)
        .first()
    )
    if device:
        tenant = db.get(Tenant, device.tenant_id)
        return ("device", device, tenant)

    tenant = (
        db.query(Tenant)
        .filter(Tenant.service_token == raw, Tenant.is_active == 1)
        .first()
    )
    if tenant:
        return ("crm", None, tenant)

    raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token")
