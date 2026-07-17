from pathlib import Path

import cv2
import numpy as np
from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile, status
from sqlalchemy.orm import Session

from app.auth import require_crm_or_device, require_crm_token
from app.config import get_settings
from app.db import get_db
from app.face_engine import average_embeddings, decode_image_bytes, extract_embedding
from app.models import FaceImage, FaceTemplate, Student
from app.schemas import (
    EmbeddingOut,
    EnrollResponse,
    StudentBulkSyncIn,
    StudentBulkSyncOut,
    StudentCreate,
    StudentListItem,
    StudentOut,
)

router = APIRouter(prefix="/students", tags=["students"])
settings = get_settings()


@router.post("", response_model=StudentOut, dependencies=[Depends(require_crm_token)])
def upsert_student(body: StudentCreate, db: Session = Depends(get_db)) -> Student:
    student = _upsert_student(body, db)
    db.commit()
    db.refresh(student)
    return student


@router.post(
    "/bulk-sync",
    response_model=StudentBulkSyncOut,
    dependencies=[Depends(require_crm_token)],
)
def bulk_sync_students(
    body: StudentBulkSyncIn,
    db: Session = Depends(get_db),
) -> StudentBulkSyncOut:
    for student in body.students:
        _upsert_student(student, db)
    db.commit()
    return StudentBulkSyncOut(synced=len(body.students))


@router.get("", response_model=list[StudentListItem])
def list_students(
    db: Session = Depends(get_db),
    _auth=Depends(require_crm_or_device),
) -> list[StudentListItem]:
    students = db.query(Student).order_by(Student.enrollment_number.asc()).all()
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
            )
        )
    return items


def _upsert_student(body: StudentCreate, db: Session) -> Student:
    enrollment = body.enrollment_number.strip().upper()
    existing = None
    if body.id:
        existing = db.get(Student, body.id)
    if existing is None:
        existing = db.query(Student).filter(Student.enrollment_number == enrollment).first()

    if existing:
        existing.name = body.name
        existing.batch = body.batch
        existing.enrollment_number = enrollment
        if body.crm_student_id:
            existing.crm_student_id = body.crm_student_id
        return existing

    student = Student(
        enrollment_number=enrollment,
        name=body.name,
        batch=body.batch,
        crm_student_id=body.crm_student_id,
    )
    if body.id:
        student.id = body.id
    db.add(student)
    return student


@router.get("/{student_id}", response_model=StudentOut)
def get_student(
    student_id: str,
    db: Session = Depends(get_db),
    _auth=Depends(require_crm_or_device),
) -> Student:
    student = db.get(Student, student_id)
    if not student:
        raise HTTPException(status_code=404, detail="Student not found")
    return student


@router.post("/{student_id}/enroll", response_model=EnrollResponse)
async def enroll_student(
    student_id: str,
    images: list[UploadFile] = File(...),
    angles: str | None = Form(default=None),
    name: str | None = Form(default=None),
    db: Session = Depends(get_db),
    _auth=Depends(require_crm_or_device),
) -> EnrollResponse:
    # Accept UUID or enrollment_number (e.g. STU001 / FI 0801)
    student = db.get(Student, student_id)
    if student is None:
        enr = student_id.strip().upper()
        student = db.query(Student).filter(Student.enrollment_number == enr).first()
    if not student:
        # Kiosk can register a new student while capturing faces.
        enr = student_id.strip().upper()
        if not enr:
            raise HTTPException(status_code=404, detail="Student not found")
        display_name = (name or enr).strip() or enr
        student = Student(enrollment_number=enr, name=display_name)
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
    face_dir = Path(settings.faces_dir) / student_id
    face_dir.mkdir(parents=True, exist_ok=True)

    for idx, upload in enumerate(images):
        raw = await upload.read()
        try:
            img = decode_image_bytes(raw)
            emb = extract_embedding(img)
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=f"Image {idx + 1}: {exc}") from exc

        embeddings.append(emb)
        angle = angle_list[idx] if idx < len(angle_list) else f"shot_{idx + 1}"
        filename = f"{angle}_{idx + 1}.jpg"
        path = face_dir / filename
        cv2.imwrite(str(path), img)
        db.add(FaceImage(student_id=student_id, path=str(path), angle=angle))

    template_vec = average_embeddings(embeddings).tolist()
    template = (
        db.query(FaceTemplate)
        .filter(
            FaceTemplate.student_id == student_id,
            FaceTemplate.model_version == settings.face_model_version,
        )
        .first()
    )
    if template:
        template.embedding = template_vec
        template.image_count = len(embeddings)
    else:
        template = FaceTemplate(
            student_id=student_id,
            embedding=template_vec,
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
    _auth=Depends(require_crm_or_device),
) -> EmbeddingOut:
    student = db.get(Student, student_id)
    if not student:
        raise HTTPException(status_code=404, detail="Student not found")
    template = (
        db.query(FaceTemplate)
        .filter(
            FaceTemplate.student_id == student_id,
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
