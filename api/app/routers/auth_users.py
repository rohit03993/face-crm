from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.orm import Session

from app.auth import (
    create_user_session,
    hash_password,
    require_app_admin,
    require_app_user,
    require_device,
    verify_password,
)
from app.db import get_db
from app.models import AppSession, AppUser, AppUserRole, Device
from app.schemas import (
    AppAuthOut,
    AppUserBootstrapIn,
    AppUserCreateStaffIn,
    AppUserLoginIn,
    AppUserOut,
)

router = APIRouter(prefix="/auth", tags=["auth"])


def _normalize_email(email: str) -> str:
    return email.strip().lower()


def _user_out(user: AppUser) -> AppUserOut:
    return AppUserOut(
        id=user.id,
        email=user.email,
        name=user.name,
        role=user.role,
        is_active=user.is_active == 1,
    )


@router.get("/status")
def auth_status(
    db: Session = Depends(get_db),
    device: Device = Depends(require_device),
) -> dict:
    """APK uses this to know if first admin bootstrap is needed."""
    count = (
        db.query(AppUser)
        .filter(AppUser.tenant_id == device.tenant_id, AppUser.is_active == 1)
        .count()
    )
    return {
        "has_users": count > 0,
        "needs_bootstrap": count == 0,
        "tenant_id": device.tenant_id,
    }


@router.post("/bootstrap", response_model=AppAuthOut)
def bootstrap_admin(
    body: AppUserBootstrapIn,
    db: Session = Depends(get_db),
    device: Device = Depends(require_device),
) -> AppAuthOut:
    """Create the first school admin for this device's tenant. One-time only."""
    existing = (
        db.query(AppUser)
        .filter(AppUser.tenant_id == device.tenant_id)
        .count()
    )
    if existing > 0:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Admin already exists — use login instead",
        )
    email = _normalize_email(body.email)
    if "@" not in email:
        raise HTTPException(status_code=400, detail="Enter a valid email")
    user = AppUser(
        tenant_id=device.tenant_id,
        email=email,
        name=body.name.strip(),
        password_hash=hash_password(body.password),
        role=AppUserRole.ADMIN.value,
        is_active=1,
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    token = create_user_session(db, user)
    return AppAuthOut(
        user_token=token,
        user=_user_out(user),
        message="First admin created",
    )


@router.post("/login", response_model=AppAuthOut)
def login(
    body: AppUserLoginIn,
    db: Session = Depends(get_db),
    device: Device = Depends(require_device),
) -> AppAuthOut:
    email = _normalize_email(body.email)
    user = (
        db.query(AppUser)
        .filter(
            AppUser.tenant_id == device.tenant_id,
            AppUser.email == email,
            AppUser.is_active == 1,
        )
        .first()
    )
    if not user or not verify_password(body.password, user.password_hash):
        raise HTTPException(status_code=401, detail="Invalid email or password")
    token = create_user_session(db, user)
    return AppAuthOut(user_token=token, user=_user_out(user))


@router.post("/logout")
def logout(
    db: Session = Depends(get_db),
    user: AppUser = Depends(require_app_user),
) -> dict:
    db.query(AppSession).filter(AppSession.user_id == user.id).delete()
    db.commit()
    return {"ok": True}


@router.get("/me", response_model=AppUserOut)
def me(user: AppUser = Depends(require_app_user)) -> AppUserOut:
    return _user_out(user)


@router.get("/users", response_model=list[AppUserOut])
def list_users(
    db: Session = Depends(get_db),
    admin: AppUser = Depends(require_app_admin),
) -> list[AppUserOut]:
    rows = (
        db.query(AppUser)
        .filter(AppUser.tenant_id == admin.tenant_id)
        .order_by(AppUser.created_at.asc())
        .all()
    )
    return [_user_out(u) for u in rows]


@router.post("/users", response_model=AppUserOut)
def create_staff(
    body: AppUserCreateStaffIn,
    db: Session = Depends(get_db),
    admin: AppUser = Depends(require_app_admin),
) -> AppUserOut:
    email = _normalize_email(body.email)
    if "@" not in email:
        raise HTTPException(status_code=400, detail="Enter a valid email")
    exists = (
        db.query(AppUser)
        .filter(AppUser.tenant_id == admin.tenant_id, AppUser.email == email)
        .first()
    )
    if exists:
        raise HTTPException(status_code=400, detail="Email already registered")
    user = AppUser(
        tenant_id=admin.tenant_id,
        email=email,
        name=body.name.strip(),
        password_hash=hash_password(body.password),
        role=AppUserRole.STAFF.value,
        is_active=1,
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    return _user_out(user)


@router.delete("/users/{user_id}")
def deactivate_user(
    user_id: str,
    db: Session = Depends(get_db),
    admin: AppUser = Depends(require_app_admin),
) -> dict:
    if user_id == admin.id:
        raise HTTPException(status_code=400, detail="Cannot deactivate yourself")
    user = (
        db.query(AppUser)
        .filter(AppUser.id == user_id, AppUser.tenant_id == admin.tenant_id)
        .first()
    )
    if not user:
        raise HTTPException(status_code=404, detail="User not found")
    user.is_active = 0
    db.query(AppSession).filter(AppSession.user_id == user.id).delete()
    db.commit()
    return {"ok": True, "id": user.id}
