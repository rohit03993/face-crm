import hashlib
import hmac
import secrets

from fastapi import Depends, HTTPException, Security, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.orm import Session

from app.config import get_settings
from app.db import get_db
from app.models import Device

bearer = HTTPBearer(auto_error=False)
settings = get_settings()


def hash_token(raw: str) -> str:
    peppered = f"{settings.device_token_pepper}:{raw}".encode()
    return hashlib.sha256(peppered).hexdigest()


def generate_token() -> str:
    return secrets.token_urlsafe(32)


def require_crm_token(
    credentials: HTTPAuthorizationCredentials | None = Security(bearer),
) -> None:
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing bearer token")
    if not hmac.compare_digest(credentials.credentials, settings.crm_service_token):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid CRM token")


def require_device(
    credentials: HTTPAuthorizationCredentials | None = Security(bearer),
    db: Session = Depends(get_db),
) -> Device:
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing bearer token")
    token_hash = hash_token(credentials.credentials)
    device = db.query(Device).filter(Device.token_hash == token_hash, Device.is_active == 1).first()
    if not device:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid device token")
    return device


def require_crm_or_device(
    credentials: HTTPAuthorizationCredentials | None = Security(bearer),
    db: Session = Depends(get_db),
) -> tuple[str, Device | None]:
    """Returns ('crm', None) or ('device', Device)."""
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Missing bearer token")
    if hmac.compare_digest(credentials.credentials, settings.crm_service_token):
        return ("crm", None)
    token_hash = hash_token(credentials.credentials)
    device = db.query(Device).filter(Device.token_hash == token_hash, Device.is_active == 1).first()
    if device:
        return ("device", device)
    raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token")
