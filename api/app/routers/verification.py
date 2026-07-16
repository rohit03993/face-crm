from datetime import datetime, timezone
from pathlib import Path

import cv2
from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile
from sqlalchemy.orm import Session

from app.auth import require_crm_token, require_device
from app.config import get_settings
from app.crm_callback import notify_crm_pass
from app.db import get_db
from app.face_engine import decode_image_bytes
from app.models import Device, FailCapture, FaceTemplate, Student, VerificationRequest, VerificationStatus
from app.schemas import VerificationRequestCreate, VerificationRequestOut, WsVerificationPayload
from app.ws_hub import hub

router = APIRouter(tags=["verification"])
settings = get_settings()


@router.post(
    "/verification-requests",
    response_model=VerificationRequestOut,
    dependencies=[Depends(require_crm_token)],
)
async def create_verification_request(
    body: VerificationRequestCreate,
    db: Session = Depends(get_db),
) -> VerificationRequest:
    device = db.get(Device, body.device_id)
    if not device or not device.is_active:
        raise HTTPException(status_code=404, detail="Device not found")

    student: Student | None = None
    if body.student_id:
        student = db.get(Student, body.student_id)
    elif body.enrollment_number:
        enr = body.enrollment_number.strip().upper()
        student = db.query(Student).filter(Student.enrollment_number == enr).first()
    if not student:
        raise HTTPException(status_code=404, detail="Student not found")

    template = (
        db.query(FaceTemplate)
        .filter(
            FaceTemplate.student_id == student.id,
            FaceTemplate.model_version == settings.face_model_version,
        )
        .first()
    )
    if not template:
        raise HTTPException(status_code=400, detail="Student has no enrolled face template")

    req = VerificationRequest(
        student_id=student.id,
        device_id=device.id,
        status=VerificationStatus.PENDING,
        crm_request_id=body.crm_request_id,
        meta=body.meta,
    )
    db.add(req)
    db.commit()
    db.refresh(req)

    payload = WsVerificationPayload(
        request_id=req.id,
        student_id=student.id,
        enrollment_number=student.enrollment_number,
        name=student.name,
        model_version=template.model_version,
        embedding=template.embedding,
        threshold=settings.match_threshold,
        timeout_seconds=settings.verification_timeout_seconds,
    )
    sent = await hub.send_json(device.id, payload.model_dump())
    if not sent:
        # Keep PENDING; kiosk may reconnect and CRM can retry/poll. Record note in meta.
        meta = dict(req.meta or {})
        meta["ws_delivery"] = "device_offline"
        req.meta = meta
        db.commit()
        db.refresh(req)

    return req


@router.post("/verification-results", response_model=VerificationRequestOut)
async def submit_verification_result(
    request_id: str = Form(...),
    score: float = Form(...),
    passed: bool = Form(...),
    note: str | None = Form(default=None),
    fail_image: UploadFile | None = File(default=None),
    db: Session = Depends(get_db),
    device: Device = Depends(require_device),
) -> VerificationRequest:
    req = db.get(VerificationRequest, request_id)
    if not req:
        raise HTTPException(status_code=404, detail="Request not found")
    if req.device_id != device.id:
        raise HTTPException(status_code=403, detail="Request belongs to another device")
    if req.status != VerificationStatus.PENDING:
        raise HTTPException(status_code=409, detail=f"Request already {req.status.value}")

    req.score = score
    req.status = VerificationStatus.PASS if passed else VerificationStatus.FAIL
    req.resolved_at = datetime.now(timezone.utc)

    if fail_image is not None and not passed:
        raw = await fail_image.read()
        fail_dir = Path(settings.fails_dir) / req.id
        fail_dir.mkdir(parents=True, exist_ok=True)
        path = fail_dir / "capture.jpg"
        try:
            img = decode_image_bytes(raw)
            cv2.imwrite(str(path), img)
        except ValueError:
            path.write_bytes(raw)
        db.add(FailCapture(request_id=req.id, image_path=str(path), note=note))

    db.commit()
    db.refresh(req)

    if passed:
        student = db.get(Student, req.student_id)
        enrollment = student.enrollment_number if student else ""
        await notify_crm_pass(req, enrollment)

    return req


@router.get(
    "/verification-requests/{request_id}",
    response_model=VerificationRequestOut,
    dependencies=[Depends(require_crm_token)],
)
def get_verification_request(request_id: str, db: Session = Depends(get_db)) -> VerificationRequest:
    req = db.get(VerificationRequest, request_id)
    if not req:
        raise HTTPException(status_code=404, detail="Request not found")
    return req
