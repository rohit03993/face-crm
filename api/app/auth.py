import hashlib
import hmac
import secrets
from datetime import datetime, timedelta, timezone

from fastapi import Depends, HTTPException, Security, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.orm import Session

from app.config import get_settings
from app.db import get_db
from app.models import AppSession, AppUser, Device, Tenant

bearer = HTTPBearer(auto_error=False)
settings = get_settings()

_PBKDF2_ROUNDS = 120_000


def hash_token(raw: str) -> str:
    peppered = f"{settings.device_token_pepper}:{raw}".encode()
    return hashlib.sha256(peppered).hexdigest()


def generate_token() -> str:
    return secrets.token_urlsafe(32)


def hash_password(password: str) -> str:
    salt = secrets.token_hex(16)
    digest = hashlib.pbkdf2_hmac(
        "sha256",
        password.encode("utf-8"),
        f"{settings.device_token_pepper}:{salt}".encode(),
        _PBKDF2_ROUNDS,
    )
    return f"pbkdf2${_PBKDF2_ROUNDS}${salt}${digest.hex()}"


def verify_password(password: str, stored: str) -> bool:
    try:
        algo, rounds_s, salt, digest_hex = stored.split("$", 3)
        if algo != "pbkdf2":
            return False
        rounds = int(rounds_s)
        digest = hashlib.pbkdf2_hmac(
            "sha256",
            password.encode("utf-8"),
            f"{settings.device_token_pepper}:{salt}".encode(),
            rounds,
        )
        return hmac.compare_digest(digest.hex(), digest_hex)
    except Exception:
        return False


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


def create_user_session(db: Session, user: AppUser, days: int = 30) -> str:
    plain = generate_token()
    session = AppSession(
        user_id=user.id,
        token_hash=hash_token(plain),
        expires_at=datetime.now(timezone.utc) + timedelta(days=days),
    )
    db.add(session)
    db.commit()
    return plain


def require_app_user(
    credentials: HTTPAuthorizationCredentials | None = Security(bearer),
    db: Session = Depends(get_db),
) -> AppUser:
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing bearer token")
    token_hash = hash_token(credentials.credentials)
    session = (
        db.query(AppSession)
        .filter(AppSession.token_hash == token_hash)
        .first()
    )
    if not session:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid user session")
    if session.expires_at is not None:
        exp = session.expires_at
        if exp.tzinfo is None:
            exp = exp.replace(tzinfo=timezone.utc)
        if exp < datetime.now(timezone.utc):
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Session expired")
    user = db.get(AppUser, session.user_id)
    if not user or user.is_active != 1:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="User inactive")
    return user


def require_app_admin(user: AppUser = Depends(require_app_user)) -> AppUser:
    if user.role != "admin":
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Admin only")
    return user
