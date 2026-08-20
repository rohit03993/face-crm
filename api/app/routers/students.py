import shutil
from pathlib import Path

import cv2
import numpy as np
from fastapi import APIRouter, Depends, File, Form, HTTPException, Query, UploadFile, status
from fastapi.responses import FileResponse
from sqlalchemy.orm import Session

from app.auth import require_crm_or_device, require_crm_or_device_admin, require_crm_token, require_tenant_crm
from app.config import get_settings
from app.db import get_db
from app.face_engine import average_embeddings, decode_image_bytes, extract_embedding
from app.models import FaceImage, FaceTemplate, FailCapture, Student, Tenant, VerificationRequest
from app.schemas import (
    EmbeddingOut,
    EnrollResponse,
    EnrollTemplateIn,
    FaceCheckIn,
    FaceCheckOut,
    StudentBulkRemoveIn,
    StudentBulkRemoveOut,
    StudentBulkSyncIn,
    StudentBulkSyncOut,
    StudentCreate,
    StudentListItem,
    StudentOut,
    StudentRemoveByEnrollmentIn,
    StudentRemoveOut,
    StudentUpdate,
    SyncHealthOut,
    SyncHealthPerson,
)

router = APIRouter(prefix="/students", tags=["students"])
settings = get_settings()


@router.post("", response_model=StudentOut)
def upsert_student(
    body: StudentCreate,
    db: Session = Depends(get_db),
    tenant: Tenant = Depends(require_tenant_crm),
) -> Student:
    student = _upsert_student(body, db, tenant_id=tenant.id)
    db.commit()
    db.refresh(student)
    return student


@router.post("/bulk-sync", response_model=StudentBulkSyncOut)
def bulk_sync_students(
    body: StudentBulkSyncIn,
    db: Session = Depends(get_db),
    tenant: Tenant = Depends(require_tenant_crm),
) -> StudentBulkSyncOut:
    created = 0
    updated = 0
    skipped = 0
    for student in body.students:
        existing_before = _find_existing_student(student, db, tenant_id=tenant.id)
        row = _upsert_student(student, db, tenant_id=tenant.id)
        if existing_before is None:
            created += 1
        elif row is existing_before:
            updated += 1
        else:
            skipped += 1
    db.commit()
    return StudentBulkSyncOut(
        synced=len(body.students),
        created=created,
        updated=updated,
        skipped=skipped,
    )


@router.get("", response_model=list[StudentListItem])
def list_students(
    subject: str | None = Query(
        default=None,
        description="Filter by subject: student | staff. Omit to return all.",
    ),
    db: Session = Depends(get_db),
    auth=Depends(require_crm_or_device),
) -> list[StudentListItem]:
    _kind, device, tenant = auth
    q = db.query(Student)
    if device is not None:
        q = q.filter(Student.tenant_id == device.tenant_id)
    elif tenant is not None:
        q = q.filter(Student.tenant_id == tenant.id)
    subject_norm = _normalize_subject(subject) if subject else None
    if subject_norm:
        q = q.filter(Student.subject == subject_norm)
    students = q.order_by(Student.enrollment_number.asc()).all()
    items: list[StudentListItem] = []
    for student in students:
        template = (
            db.query(FaceTemplate)
            .filter(
                FaceTemplate.student_id == student.id,
                FaceTemplate.model_version == settings.face_model_version,
            )
            .first()
        )
        items.append(
            StudentListItem(
                id=student.id,
                enrollment_number=student.enrollment_number,
                name=student.name,
                batch=student.batch,
                enrolled=template is not None,
                image_count=template.image_count if template else 0,
                has_face_photo=_student_has_face_photo(db, student.id),
                subject=getattr(student, "subject", None) or "student",
            )
        )
    return items


@router.get("/sync-health", response_model=SyncHealthOut)
def sync_health(
    db: Session = Depends(get_db),
    auth=Depends(require_crm_or_device_admin),
) -> SyncHealthOut:
    """Admin: student vs staff counts + likely orphans (missing CRM id)."""
    tenant_id = _tenant_id_from_auth(auth)
    if not tenant_id:
        raise HTTPException(status_code=403, detail="School context required")
    rows = db.query(Student).filter(Student.tenant_id == tenant_id).all()
    student_count = 0
    staff_count = 0
    orphans: list[SyncHealthPerson] = []
    for student in rows:
        subject = getattr(student, "subject", None) or "student"
        if subject == "staff":
            staff_count += 1
        else:
            student_count += 1
        template = (
            db.query(FaceTemplate)
            .filter(
                FaceTemplate.student_id == student.id,
                FaceTemplate.model_version == settings.face_model_version,
            )
            .first()
        )
        reasons: list[str] = []
        if not student.crm_student_id:
            reasons.append("missing_crm_id")
        if subject == "staff" and student.crm_student_id and not str(
            student.crm_student_id
        ).startswith("staff:"):
            reasons.append("staff_without_staff_crm_prefix")
        if reasons:
            orphans.append(
                SyncHealthPerson(
                    id=student.id,
                    enrollment_number=student.enrollment_number,
                    name=student.name,
                    batch=student.batch,
                    subject=subject,
                    crm_student_id=student.crm_student_id,
                    enrolled=template is not None,
                    reason=",".join(reasons),
                )
            )
    orphans.sort(key=lambda p: (p.subject, p.enrollment_number))
    return SyncHealthOut(
        student_count=student_count,
        staff_count=staff_count,
        total_count=len(rows),
        missing_crm_id_count=sum(1 for o in orphans if "missing_crm_id" in o.reason),
        orphans=orphans,
    )


@router.get("/roster.csv")
def roster_csv(
    db: Session = Depends(get_db),
    auth=Depends(require_crm_or_device_admin),
):
    """Admin: export Face roster for CRM diff (enrollment, subject, name, batch)."""
    import csv
    import io

    from fastapi.responses import StreamingResponse

    tenant_id = _tenant_id_from_auth(auth)
    if not tenant_id:
        raise HTTPException(status_code=403, detail="School context required")
    rows = (
        db.query(Student)
        .filter(Student.tenant_id == tenant_id)
        .order_by(Student.subject.asc(), Student.enrollment_number.asc())
        .all()
    )
    buf = io.StringIO()
    writer = csv.writer(buf)
    writer.writerow(
        [
            "enrollment_number",
            "subject",
            "name",
            "batch",
            "crm_student_id",
            "enrolled",
            "face_id",
        ]
    )
    for student in rows:
        enrolled = (
            db.query(FaceTemplate)
            .filter(
                FaceTemplate.student_id == student.id,
                FaceTemplate.model_version == settings.face_model_version,
            )
            .first()
            is not None
        )
        writer.writerow(
            [
                student.enrollment_number,
                getattr(student, "subject", None) or "student",
                student.name,
                student.batch or "",
                student.crm_student_id or "",
                "yes" if enrolled else "no",
                student.id,
            ]
        )
    buf.seek(0)
    return StreamingResponse(
        iter([buf.getvalue()]),
        media_type="text/csv",
        headers={"Content-Disposition": 'attachment; filename="face-roster.csv"'},
    )


@router.post("/remove-by-enrollment", response_model=StudentRemoveOut)
def remove_by_enrollment(
    body: StudentRemoveByEnrollmentIn,
    db: Session = Depends(get_db),
    auth=Depends(require_crm_or_device_admin),
) -> StudentRemoveOut:
    """CRM-safe delete by roll. Idempotent. Will not delete staff when subject=student."""
    tenant_id = _tenant_id_from_auth(auth)
    if not tenant_id:
        raise HTTPException(status_code=403, detail="School context required")
    enr = body.enrollment_number.strip().upper()
    want_subject = _normalize_subject(body.subject)
    student = (
        db.query(Student)
        .filter(
            Student.tenant_id == tenant_id,
            Student.enrollment_number == enr,
            Student.subject == want_subject,
        )
        .first()
    )
    if not student:
        return StudentRemoveOut(ok=True, deleted=None, already_gone=True)
    sid = student.id
    _delete_student_cascade(db, student)
    db.commit()
    return StudentRemoveOut(ok=True, deleted=sid, already_gone=False)


@router.post("/bulk-remove", response_model=StudentBulkRemoveOut)
def bulk_remove_students(
    body: StudentBulkRemoveIn,
    db: Session = Depends(get_db),
    auth=Depends(require_crm_or_device_admin),
) -> StudentBulkRemoveOut:
    """Admin: delete selected Face people (orphan cleanup)."""
    tenant_id = _tenant_id_from_auth(auth)
    if not tenant_id:
        raise HTTPException(status_code=403, detail="School context required")
    deleted = 0
    missing = 0
    for sid in body.student_ids:
        student = db.get(Student, sid.strip())
        if not student or student.tenant_id != tenant_id:
            missing += 1
            continue
        _delete_student_cascade(db, student)
        deleted += 1
    db.commit()
    return StudentBulkRemoveOut(ok=True, deleted=deleted, missing=missing)


def _normalize_subject(value: str | None) -> str:
    raw = (value or "student").strip().lower()
    if raw in {"staff", "teacher", "employee"}:
        return "staff"
    return "student"


def _find_existing_student(body: StudentCreate, db: Session, *, tenant_id: str) -> Student | None:
    if body.id:
        existing = db.get(Student, body.id)
        if existing and existing.tenant_id != tenant_id:
            raise HTTPException(status_code=403, detail="Student belongs to another school")
        if existing:
            return existing
    crm_id = (body.crm_student_id or "").strip() or None
    if not crm_id and body.crm_user_id and _normalize_subject(body.subject) == "staff":
        crm_id = f"staff:{body.crm_user_id}"
    if crm_id:
        by_crm = (
            db.query(Student)
            .filter(Student.tenant_id == tenant_id, Student.crm_student_id == crm_id)
            .first()
        )
        if by_crm:
            return by_crm
    enrollment = body.enrollment_number.strip().upper()
    return (
        db.query(Student)
        .filter(Student.tenant_id == tenant_id, Student.enrollment_number == enrollment)
        .first()
    )


def _upsert_student(body: StudentCreate, db: Session, *, tenant_id: str) -> Student:
    enrollment = body.enrollment_number.strip().upper()
    subject = _normalize_subject(body.subject)
    # Prefer explicit subject; also treat CRM staff ids like "staff:123"
    if body.crm_student_id and str(body.crm_student_id).startswith("staff:"):
        subject = "staff"
    if body.crm_user_id and subject == "student" and body.subject and body.subject.lower() == "staff":
        subject = "staff"

    existing = _find_existing_student(body, db, tenant_id=tenant_id)

    if existing:
        existing.name = body.name
        existing.batch = body.batch
        existing.enrollment_number = enrollment
        existing.subject = subject
        if body.crm_student_id:
            existing.crm_student_id = body.crm_student_id
        elif body.crm_user_id and subject == "staff":
            existing.crm_student_id = f"staff:{body.crm_user_id}"
        return existing

    crm_id = body.crm_student_id
    if not crm_id and body.crm_user_id and subject == "staff":
        crm_id = f"staff:{body.crm_user_id}"

    student = Student(
        tenant_id=tenant_id,
        enrollment_number=enrollment,
        name=body.name,
        batch=body.batch,
        crm_student_id=crm_id,
        subject=subject,
    )
    if body.id:
        student.id = body.id
    db.add(student)
    return student


def _tenant_id_from_auth(auth: tuple) -> str | None:
    _kind, device, tenant = auth
    if device is not None:
        return device.tenant_id
    if tenant is not None:
        return tenant.id
    return None


def _resolve_student(db: Session, student_id: str, *, tenant_id: str | None = None) -> Student:
    student = db.get(Student, student_id)
    if student is None:
        enr = student_id.strip().upper()
        q = db.query(Student).filter(Student.enrollment_number == enr)
        if tenant_id:
            q = q.filter(Student.tenant_id == tenant_id)
        student = q.first()
    if not student:
        raise HTTPException(status_code=404, detail="Student not found")
    if tenant_id and student.tenant_id != tenant_id:
        raise HTTPException(status_code=404, detail="Student not found")
    return student


def _enrollment_photo_path(student_id: str) -> Path:
    return Path(settings.faces_dir) / student_id / "enrollment.jpg"


def _student_has_face_photo(db: Session, student_id: str) -> bool:
    row = (
        db.query(FaceImage)
        .filter(FaceImage.student_id == student_id)
        .order_by(FaceImage.created_at.desc())
        .first()
    )
    if row is None:
        return False
    return Path(row.path).is_file()


def _resolve_face_photo_path(db: Session, student_id: str) -> Path | None:
    row = (
        db.query(FaceImage)
        .filter(FaceImage.student_id == student_id)
        .order_by(FaceImage.created_at.desc())
        .first()
    )
    if row is None:
        return None
    path = Path(row.path)
    return path if path.is_file() else None


def _save_enrollment_photo(db: Session, student_id: str, raw: bytes) -> Path:
    try:
        img = decode_image_bytes(raw)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=f"Invalid image: {exc}") from exc

    face_dir = Path(settings.faces_dir) / student_id
    face_dir.mkdir(parents=True, exist_ok=True)
    path = _enrollment_photo_path(student_id)
    cv2.imwrite(str(path), img)

    for old in db.query(FaceImage).filter(FaceImage.student_id == student_id).all():
        if old.path != str(path):
            try:
                Path(old.path).unlink(missing_ok=True)
            except OSError:
                pass
        db.delete(old)
    db.add(FaceImage(student_id=student_id, path=str(path), angle="enrollment"))
    return path


def _clear_face_data(db: Session, student_id: str) -> None:
    """Remove face images + templates for a student (DB rows and files on disk)."""
    images = db.query(FaceImage).filter(FaceImage.student_id == student_id).all()
    for img in images:
        try:
            Path(img.path).unlink(missing_ok=True)
        except OSError:
            pass
        db.delete(img)
    db.query(FaceTemplate).filter(FaceTemplate.student_id == student_id).delete()
    face_dir = Path(settings.faces_dir) / student_id
    if face_dir.exists():
        shutil.rmtree(face_dir, ignore_errors=True)


def _apply_student_update(student: Student, body: StudentUpdate, db: Session) -> Student:
    if body.name is not None:
        student.name = body.name.strip()
    if body.batch is not None:
        student.batch = body.batch.strip() or None
    if body.subject is not None:
        student.subject = _normalize_subject(body.subject)
    if body.enrollment_number is not None:
        new_enr = body.enrollment_number.strip().upper()
        clash = (
            db.query(Student)
            .filter(
                Student.tenant_id == student.tenant_id,
                Student.enrollment_number == new_enr,
                Student.id != student.id,
            )
            .first()
        )
        if clash:
            raise HTTPException(status_code=409, detail="Enrollment number already in use")
        student.enrollment_number = new_enr
    return student


def _delete_student_cascade(db: Session, student: Student) -> None:
    sid = student.id
    req_ids = [
        rid
        for (rid,) in db.query(VerificationRequest.id)
        .filter(VerificationRequest.student_id == sid)
        .all()
    ]
    if req_ids:
        db.query(FailCapture).filter(FailCapture.request_id.in_(req_ids)).delete(
            synchronize_session=False
        )
        db.query(VerificationRequest).filter(VerificationRequest.student_id == sid).delete(
            synchronize_session=False
        )
    _clear_face_data(db, sid)
    db.delete(student)


def _normalize_embedding(raw: list[float] | np.ndarray) -> np.ndarray:
    emb = np.asarray(raw, dtype=np.float32)
    if emb.shape != (settings.embedding_dim,) or not np.all(np.isfinite(emb)):
        raise HTTPException(status_code=422, detail="Invalid embedding")
    norm = float(np.linalg.norm(emb))
    if norm < 1e-6:
        raise HTTPException(status_code=422, detail="Zero embedding")
    return (emb / norm).astype(np.float32)


def _find_duplicate_face(
    db: Session,
    embedding: np.ndarray,
    *,
    exclude_student_id: str | None = None,
    tenant_id: str | None = None,
) -> tuple[Student, float] | None:
    """Return best matching enrolled student if similarity looks like the same person."""
    threshold = float(settings.duplicate_face_threshold)
    q = (
        db.query(FaceTemplate, Student)
        .join(Student, Student.id == FaceTemplate.student_id)
        .filter(FaceTemplate.model_version == settings.face_model_version)
    )
    if tenant_id:
        q = q.filter(Student.tenant_id == tenant_id)
    enrolled = q.all()
    best_student: Student | None = None
    best_score = -1.0
    for template, student in enrolled:
        if exclude_student_id and student.id == exclude_student_id:
            continue
        candidate = np.asarray(template.embedding, dtype=np.float32)
        if candidate.shape != (settings.embedding_dim,) or not np.all(np.isfinite(candidate)):
            continue
        cnorm = float(np.linalg.norm(candidate))
        if cnorm < 1e-6:
            continue
        score = float(np.dot(embedding, candidate / cnorm))
        if score > best_score:
            best_score = score
            best_student = student
    if best_student is None or best_score < threshold:
        return None
    return best_student, best_score


def _reject_if_duplicate(
    db: Session,
    embedding: np.ndarray,
    *,
    exclude_student_id: str | None = None,
    tenant_id: str | None = None,
) -> None:
    hit = _find_duplicate_face(
        db,
        embedding,
        exclude_student_id=exclude_student_id,
        tenant_id=tenant_id,
    )
    if hit is None:
        return
    student, score = hit
    raise HTTPException(
        status_code=409,
        detail=(
            f"This face already belongs to {student.name} "
            f"({student.enrollment_number}). "
            f"Capture the correct person, or update that student instead. "
            f"Score {score:.2f}"
        ),
    )


def _save_phone_template(
    db: Session,
    *,
    student_key: str,
    body: EnrollTemplateIn,
    tenant_id: str,
) -> EnrollResponse:
    if body.model_version != settings.face_model_version:
        raise HTTPException(
            status_code=409,
            detail=(
                f"Model mismatch: kiosk={body.model_version}, "
                f"server={settings.face_model_version}"
            ),
        )

    emb = _normalize_embedding(body.embedding)

    student = db.get(Student, student_key)
    if student is not None and student.tenant_id != tenant_id:
        raise HTTPException(status_code=404, detail="Student not found")
    if student is None:
        enr = student_key.strip().upper()
        student = (
            db.query(Student)
            .filter(Student.tenant_id == tenant_id, Student.enrollment_number == enr)
            .first()
        )
    if not student:
        enr = (body.enrollment_number or student_key).strip().upper()
        if not enr:
            raise HTTPException(status_code=404, detail="Student not found")
        display_name = (body.name or enr).strip() or enr
        student = Student(tenant_id=tenant_id, enrollment_number=enr, name=display_name)
        db.add(student)
        db.flush()
    elif body.name and body.name.strip() and student.name != body.name.strip():
        student.name = body.name.strip()

    sid = student.id
    # Block saving Rohit's face under Neha (etc.). Re-enrolling the same student is OK.
    _reject_if_duplicate(db, emb, exclude_student_id=sid, tenant_id=tenant_id)

    _clear_face_data(db, sid)
    db.flush()

    template = FaceTemplate(
        student_id=sid,
        embedding=emb.tolist(),
        model_version=settings.face_model_version,
        image_count=body.image_count,
    )
    db.add(template)
    db.commit()

    return EnrollResponse(
        student_id=sid,
        model_version=settings.face_model_version,
        image_count=body.image_count,
        embedding_dim=settings.embedding_dim,
    )


# --- Device-friendly POST routes (some networks block PATCH/DELETE) ---


@router.post("/check-face", response_model=FaceCheckOut)
def check_face_duplicate(
    body: FaceCheckIn,
    db: Session = Depends(get_db),
    auth=Depends(require_crm_or_device),
) -> FaceCheckOut:
    """Kiosk can call this after a capture to warn early about duplicate faces."""
    if body.model_version != settings.face_model_version:
        raise HTTPException(
            status_code=409,
            detail=(
                f"Model mismatch: kiosk={body.model_version}, "
                f"server={settings.face_model_version}"
            ),
        )
    emb = _normalize_embedding(body.embedding)
    threshold = float(settings.duplicate_face_threshold)
    tenant_id = _tenant_id_from_auth(auth)
    hit = _find_duplicate_face(
        db,
        emb,
        exclude_student_id=body.exclude_student_id,
        tenant_id=tenant_id,
    )
    if hit is None:
        return FaceCheckOut(
            duplicate=False,
            threshold=threshold,
            message="Face is unique enough to enroll.",
        )
    student, score = hit
    return FaceCheckOut(
        duplicate=True,
        score=score,
        threshold=threshold,
        student_id=student.id,
        enrollment_number=student.enrollment_number,
        name=student.name,
        message=(
            f"This face already belongs to {student.name} "
            f"({student.enrollment_number})."
        ),
    )


@router.post("/enroll-template", response_model=EnrollResponse)
def enroll_template_collection(
    body: EnrollTemplateIn,
    db: Session = Depends(get_db),
    auth=Depends(require_crm_or_device),
) -> EnrollResponse:
    key = (body.student_id or body.enrollment_number or "").strip()
    if not key:
        raise HTTPException(status_code=422, detail="student_id or enrollment_number required")
    tenant_id = _tenant_id_from_auth(auth)
    if not tenant_id:
        raise HTTPException(status_code=403, detail="School context required")
    return _save_phone_template(db, student_key=key, body=body, tenant_id=tenant_id)


@router.post("/{student_id}/update", response_model=StudentOut)
def update_student_post(
    student_id: str,
    body: StudentUpdate,
    db: Session = Depends(get_db),
    auth=Depends(require_crm_or_device_admin),
) -> Student:
    student = _resolve_student(db, student_id, tenant_id=_tenant_id_from_auth(auth))
    _apply_student_update(student, body, db)
    db.commit()
    db.refresh(student)
    return student


@router.post("/{student_id}/remove", response_model=StudentRemoveOut)
def remove_student_post(
    student_id: str,
    db: Session = Depends(get_db),
    auth=Depends(require_crm_or_device_admin),
) -> StudentRemoveOut:
    tenant_id = _tenant_id_from_auth(auth)
    student = db.get(Student, student_id)
    if student is None:
        enr = student_id.strip().upper()
        q = db.query(Student).filter(Student.enrollment_number == enr)
        if tenant_id:
            q = q.filter(Student.tenant_id == tenant_id)
        student = q.first()
    if not student or (tenant_id and student.tenant_id != tenant_id):
        return StudentRemoveOut(ok=True, deleted=None, already_gone=True)
    sid = student.id
    _delete_student_cascade(db, student)
    db.commit()
    return StudentRemoveOut(ok=True, deleted=sid, already_gone=False)


@router.post("/{student_id}/clear-face", status_code=status.HTTP_200_OK)
def clear_student_face(
    student_id: str,
    db: Session = Depends(get_db),
    auth=Depends(require_crm_or_device_admin),
) -> dict:
    """Admin: remove face photos/templates but keep the roster row for CRM re-enroll."""
    student = _resolve_student(db, student_id, tenant_id=_tenant_id_from_auth(auth))
    _clear_face_data(db, student.id)
    db.commit()
    return {"ok": True, "student_id": student.id, "cleared": True}


@router.get("/{student_id}", response_model=StudentOut)
def get_student(
    student_id: str,
    db: Session = Depends(get_db),
    auth=Depends(require_crm_or_device),
) -> Student:
    return _resolve_student(db, student_id, tenant_id=_tenant_id_from_auth(auth))


@router.get("/{student_id}/face-photo")
def get_face_photo(
    student_id: str,
    db: Session = Depends(get_db),
    auth=Depends(require_crm_or_device),
) -> FileResponse:
    student = _resolve_student(db, student_id, tenant_id=_tenant_id_from_auth(auth))
    path = _resolve_face_photo_path(db, student.id)
    if path is None:
        raise HTTPException(status_code=404, detail="No enrollment photo for this student")
    return FileResponse(path, media_type="image/jpeg", filename=f"{student.enrollment_number}.jpg")


@router.post("/{student_id}/face-photo", status_code=status.HTTP_200_OK)
async def upload_face_photo(
    student_id: str,
    photo: UploadFile = File(...),
    db: Session = Depends(get_db),
    auth=Depends(require_crm_or_device),
) -> dict:
    student = _resolve_student(db, student_id, tenant_id=_tenant_id_from_auth(auth))
    raw = await photo.read()
    if not raw:
        raise HTTPException(status_code=400, detail="Empty image upload")
    if len(raw) > 2_000_000:
        raise HTTPException(status_code=400, detail="Image too large (max 2MB)")
    _save_enrollment_photo(db, student.id, raw)
    db.commit()
    return {"ok": True, "student_id": student.id}


@router.patch("/{student_id}", response_model=StudentOut)
def update_student(
    student_id: str,
    body: StudentUpdate,
    db: Session = Depends(get_db),
    auth=Depends(require_crm_or_device_admin),
) -> Student:
    student = _resolve_student(db, student_id, tenant_id=_tenant_id_from_auth(auth))
    _apply_student_update(student, body, db)
    db.commit()
    db.refresh(student)
    return student


@router.delete("/{student_id}", response_model=StudentRemoveOut)
def delete_student(
    student_id: str,
    db: Session = Depends(get_db),
    auth=Depends(require_crm_or_device_admin),
) -> StudentRemoveOut:
    return remove_student_post(student_id, db, auth)


@router.post("/{student_id}/enroll-template", response_model=EnrollResponse)
def enroll_student_template(
    student_id: str,
    body: EnrollTemplateIn,
    db: Session = Depends(get_db),
    auth=Depends(require_crm_or_device),
) -> EnrollResponse:
    """Store a phone-computed face template only (no InsightFace, no image files)."""
    tenant_id = _tenant_id_from_auth(auth)
    if not tenant_id:
        raise HTTPException(status_code=403, detail="School context required")
    return _save_phone_template(db, student_key=student_id, body=body, tenant_id=tenant_id)


@router.post("/{student_id}/enroll", response_model=EnrollResponse)
async def enroll_student(
    student_id: str,
    images: list[UploadFile] = File(...),
    angles: str | None = Form(default=None),
    name: str | None = Form(default=None),
    db: Session = Depends(get_db),
    auth=Depends(require_crm_or_device),
) -> EnrollResponse:
    tenant_id = _tenant_id_from_auth(auth)
    if not tenant_id:
        raise HTTPException(status_code=403, detail="School context required")

    # Accept UUID or enrollment_number (e.g. STU001 / FI 0801)
    student = db.get(Student, student_id)
    if student is not None and student.tenant_id != tenant_id:
        raise HTTPException(status_code=404, detail="Student not found")
    if student is None:
        enr = student_id.strip().upper()
        student = (
            db.query(Student)
            .filter(Student.tenant_id == tenant_id, Student.enrollment_number == enr)
            .first()
        )
    if not student:
        # Kiosk can register a new student while capturing faces.
        enr = student_id.strip().upper()
        if not enr:
            raise HTTPException(status_code=404, detail="Student not found")
        display_name = (name or enr).strip() or enr
        student = Student(tenant_id=tenant_id, enrollment_number=enr, name=display_name)
        db.add(student)
        db.commit()
        db.refresh(student)
    elif name and name.strip() and student.name != name.strip():
        student.name = name.strip()
        db.commit()
    student_id = student.id

    if len(images) < 3 or len(images) > 6:
        raise HTTPException(status_code=400, detail="Provide 3–6 face images")

    angle_list = [a.strip() for a in angles.split(",")] if angles else []
    embeddings: list[np.ndarray] = []
    decoded: list[tuple[np.ndarray, str]] = []

    for idx, upload in enumerate(images):
        raw = await upload.read()
        try:
            img = decode_image_bytes(raw)
            emb = extract_embedding(img)
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=f"Image {idx + 1}: {exc}") from exc
        embeddings.append(emb)
        angle = angle_list[idx] if idx < len(angle_list) else f"shot_{idx + 1}"
        decoded.append((img, angle))

    template_vec = average_embeddings(embeddings)
    _reject_if_duplicate(db, template_vec, exclude_student_id=student_id, tenant_id=tenant_id)

    # Re-enroll replaces previous face photos/templates for this student.
    _clear_face_data(db, student_id)
    db.flush()

    face_dir = Path(settings.faces_dir) / student_id
    face_dir.mkdir(parents=True, exist_ok=True)

    for idx, (img, angle) in enumerate(decoded):
        filename = f"{angle}_{idx + 1}.jpg"
        path = face_dir / filename
        cv2.imwrite(str(path), img)
        db.add(FaceImage(student_id=student_id, path=str(path), angle=angle))

    template_list = template_vec.tolist()
    template = (
        db.query(FaceTemplate)
        .filter(
            FaceTemplate.student_id == student_id,
            FaceTemplate.model_version == settings.face_model_version,
        )
        .first()
    )
    if template:
        template.embedding = template_list
        template.image_count = len(embeddings)
    else:
        template = FaceTemplate(
            student_id=student_id,
            embedding=template_list,
            model_version=settings.face_model_version,
            image_count=len(embeddings),
        )
        db.add(template)

    db.commit()
    return EnrollResponse(
        student_id=student_id,
        model_version=settings.face_model_version,
        image_count=len(embeddings),
        embedding_dim=settings.embedding_dim,
    )


@router.get("/{student_id}/embedding", response_model=EmbeddingOut)
def get_embedding(
    student_id: str,
    db: Session = Depends(get_db),
    auth=Depends(require_crm_or_device),
) -> EmbeddingOut:
    student = _resolve_student(db, student_id, tenant_id=_tenant_id_from_auth(auth))
    template = (
        db.query(FaceTemplate)
        .filter(
            FaceTemplate.student_id == student.id,
            FaceTemplate.model_version == settings.face_model_version,
        )
        .first()
    )
    if not template:
        raise HTTPException(status_code=404, detail="No face template enrolled")
    return EmbeddingOut(
        student_id=student.id,
        enrollment_number=student.enrollment_number,
        model_version=template.model_version,
        embedding=template.embedding,
        image_count=template.image_count,
    )
