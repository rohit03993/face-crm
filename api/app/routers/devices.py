from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.auth import hash_token, require_crm_token
from app.db import get_db
from app.models import Device
from app.schemas import DeviceCreate, DeviceOut

router = APIRouter(prefix="/devices", tags=["devices"])


@router.post("", response_model=DeviceOut, dependencies=[Depends(require_crm_token)])
def create_device(body: DeviceCreate, db: Session = Depends(get_db)) -> Device:
    token_hash = hash_token(body.token)
    if db.query(Device).filter(Device.token_hash == token_hash).first():
        raise HTTPException(status_code=400, detail="Token already registered")
    device_id = (body.id or "").strip() or None
    if device_id and db.get(Device, device_id):
        raise HTTPException(status_code=400, detail=f"Device id {device_id} already exists")
    device = Device(
        id=device_id,
        name=body.name,
        gate=body.gate,
        token_hash=token_hash,
    ) if device_id else Device(name=body.name, gate=body.gate, token_hash=token_hash)
    db.add(device)
    db.commit()
    db.refresh(device)
    return device


@router.get("", response_model=list[DeviceOut], dependencies=[Depends(require_crm_token)])
def list_devices(db: Session = Depends(get_db)) -> list[Device]:
    return db.query(Device).order_by(Device.created_at.desc()).all()
