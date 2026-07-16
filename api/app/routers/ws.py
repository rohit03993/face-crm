from fastapi import APIRouter, Query, WebSocket, WebSocketDisconnect
from sqlalchemy.orm import Session

from app.auth import hash_token
from app.db import SessionLocal
from app.models import Device
from app.ws_hub import hub

router = APIRouter(tags=["websocket"])


def _device_from_token(token: str) -> Device | None:
    db: Session = SessionLocal()
    try:
        token_hash = hash_token(token)
        return db.query(Device).filter(Device.token_hash == token_hash, Device.is_active == 1).first()
    finally:
        db.close()


@router.websocket("/ws/kiosk/{device_id}")
async def kiosk_ws(
    websocket: WebSocket,
    device_id: str,
    token: str = Query(...),
) -> None:
    device = _device_from_token(token)
    if device is None or device.id != device_id:
        await websocket.close(code=4401, reason="unauthorized")
        return

    await hub.connect(device_id, websocket)
    try:
        await websocket.send_json({"type": "connected", "device_id": device_id})
        while True:
            # Keepalive / ignore client pings
            data = await websocket.receive_text()
            if data in ("ping", "pong"):
                await websocket.send_text("pong")
    except WebSocketDisconnect:
        await hub.disconnect(device_id, websocket)
    except Exception:
        await hub.disconnect(device_id, websocket)
        raise
