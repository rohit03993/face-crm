from datetime import datetime, timedelta, timezone
from pathlib import Path
import logging
import uuid
from zoneinfo import ZoneInfo

import cv2
import numpy as np
from fastapi import APIRouter, BackgroundTasks, Depends, File, Form, HTTPException, UploadFile
from sqlalchemy.orm import Session

from app.auth import require_crm_token, require_device
from app.config import get_settings
from app.crm_callback import notify_crm_camera_punch, notify_crm_pass
from app.db import SessionLocal, get_db
from app.face_engine import decode_image_bytes
from app.models import Device, FailCapture, FaceTemplate, Student, VerificationRequest, VerificationStatus
from app.schemas import (
    CameraIdentifyIn,
    CameraIdentifyOut,
    VerificationRequestCreate,
    VerificationRequestOut,
    WsVerificationPayload,
)
from app.ws_hub import hub

router = APIRouter(tags=["verification"])
settings = get_settings()
logger = logging.getLogger(__name__)


async def _background_crm_camera_punch(
    *,
    request_id: str,
    student_id: str,
    enrollment_number: str,
    device_id: str,
    score: float,
) -> None:
    """Punch CRM after the kiosk already got a fast match response."""
    result = await notify_crm_camera_punch(
        request_id=request_id,
        student_id=student_id,
        enrollment_number=enrollment_number,
        device_id=device_id,
        score=score,
    )
    db = SessionLocal()
    try:
        req = db.get(VerificationRequest, request_id)
        if not req:
            return
        meta = dict(req.meta or {})
        if result is None:
            meta["crm_error"] = "crm_camera_punch_failed"
            logger.error("Background CRM punch failed for %s", request_id)
        else:
            meta["crm_result"] = result
        req.meta = meta
        db.commit()
    finally:
        db.close()


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
        student = (
            db.query(Student)
            .filter(Student.tenant_id == device.tenant_id, Student.enrollment_number == enr)
            .first()
        )
    if not student or student.tenant_id != device.tenant_id:
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


@router.post("/camera-identify", response_model=CameraIdentifyOut)
async def camera_identify(
    body: CameraIdentifyIn,
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db),
    device: Device = Depends(require_device),
) -> CameraIdentifyOut:
    """Identify one live face against enrolled templates and mark CRM attendance."""
    if body.model_version != settings.face_model_version:
        raise HTTPException(
            status_code=409,
            detail=(
                f"Model mismatch: kiosk={body.model_version}, "
                f"server={settings.face_model_version}"
            ),
        )

    embedding = np.asarray(body.embedding, dtype=np.float32)
    if embedding.shape != (settings.embedding_dim,) or not np.all(np.isfinite(embedding)):
        raise HTTPException(status_code=422, detail="Invalid embedding")
    norm = float(np.linalg.norm(embedding))
    if norm < 1e-6:
        raise HTTPException(status_code=422, detail="Zero embedding")
    embedding /= norm

    enrolled = (
        db.query(FaceTemplate, Student)
        .join(Student, Student.id == FaceTemplate.student_id)
        .filter(
            FaceTemplate.model_version == settings.face_model_version,
            Student.tenant_id == device.tenant_id,
        )
        .all()
    )
    if not enrolled:
        logger.warning(
            "camera-identify: no templates for tenant=%s device=%s",
            device.tenant_id,
            device.id,
        )
        return CameraIdentifyOut(
            matched=False,
            threshold=settings.match_threshold,
            message="No students have enrolled face templates.",
        )

    best_student: Student | None = None
    best_score = -1.0
    for template, student in enrolled:
        candidate = np.asarray(template.embedding, dtype=np.float32)
        if candidate.shape != (settings.embedding_dim,) or not np.all(np.isfinite(candidate)):
            continue
        candidate_norm = float(np.linalg.norm(candidate))
        if candidate_norm < 1e-6:
            continue
        score = float(np.dot(embedding, candidate / candidate_norm))
        if score > best_score:
            best_score = score
            best_student = student

    if best_student is None or best_score < settings.match_threshold:
        logger.info(
            "camera-identify miss: best_score=%.4f threshold=%.4f templates=%d tenant=%s",
            best_score,
            settings.match_threshold,
            len(enrolled),
            device.tenant_id,
        )
        return CameraIdentifyOut(
            matched=False,
            score=max(best_score, 0.0),
            threshold=settings.match_threshold,
            message="No enrolled face matched.",
        )

    cooldown = max(0, settings.camera_punch_cooldown_seconds)
    if cooldown:
        recent_rows = (
            db.query(VerificationRequest)
            .filter(
                VerificationRequest.student_id == best_student.id,
                VerificationRequest.device_id == device.id,
                VerificationRequest.status == VerificationStatus.PASS,
                VerificationRequest.resolved_at
                >= datetime.now(timezone.utc) - timedelta(seconds=cooldown),
            )
            .order_by(VerificationRequest.resolved_at.desc())
            .limit(20)
            .all()
        )
        recent = next(
            (
                row
                for row in recent_rows
                if (row.meta or {}).get("source") == "camera_kiosk"
            ),
            None,
        )
        if recent:
            marked_at = recent.resolved_at
            if marked_at.tzinfo is None:
                marked_at = marked_at.replace(tzinfo=timezone.utc)
            marked_local = marked_at.astimezone(ZoneInfo(settings.app_timezone))
            hour = marked_local.hour % 12 or 12
            marked_label = f"{hour}:{marked_local.minute:02d} {'AM' if marked_local.hour < 12 else 'PM'}"
            return CameraIdentifyOut(
                matched=True,
                attendance_recorded=True,
                already_processed=True,
                student_id=best_student.id,
                enrollment_number=best_student.enrollment_number,
                name=best_student.name,
                score=best_score,
                threshold=settings.match_threshold,
                marked_at=marked_at.isoformat(),
                message=(
                    f"Attendance already marked at {marked_label}. "
                    "Try again after 15 minutes."
                ),
            )

    request_id = str(uuid.uuid4())
    now = datetime.now(timezone.utc)
    req = VerificationRequest(
        id=request_id,
        student_id=best_student.id,
        device_id=device.id,
        status=VerificationStatus.PASS,
        score=best_score,
        meta={"source": "camera_kiosk", "crm_pending": True},
        resolved_at=now,
    )
    db.add(req)
    db.commit()

    # Return match to kiosk immediately; CRM punch runs in the background.
    background_tasks.add_task(
        _background_crm_camera_punch,
        request_id=request_id,
        student_id=best_student.id,
        enrollment_number=best_student.enrollment_number,
        device_id=device.id,
        score=best_score,
    )

    return CameraIdentifyOut(
        matched=True,
        attendance_recorded=True,
        already_processed=False,
        student_id=best_student.id,
        enrollment_number=best_student.enrollment_number,
        name=best_student.name,
        score=best_score,
        threshold=settings.match_threshold,
        marked_at=now.isoformat(),
        message="Attendance recorded.",
    )


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
