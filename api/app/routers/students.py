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
from app.schemas import EmbeddingOut, EnrollResponse, StudentCreate, StudentOut

router = APIRouter(prefix="/students", tags=["students"])
settings = get_settings()


@router.post("", response_model=StudentOut, dependencies=[Depends(require_crm_token)])
def upsert_student(body: StudentCreate, db: Session = Depends(get_db)) -> Student:
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
        db.commit()
        db.refresh(existing)
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
    db.commit()
    db.refresh(student)
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
    db: Session = Depends(get_db),
    _auth=Depends(require_crm_or_device),
) -> EnrollResponse:
    # Accept UUID or enrollment_number (e.g. STU001)
    student = db.get(Student, student_id)
    if student is None:
        enr = student_id.strip().upper()
        student = db.query(Student).filter(Student.enrollment_number == enr).first()
    if not student:
        raise HTTPException(status_code=404, detail="Student not found")
    student_id = student.id

    if len(images) < 5 or len(images) > 10:
        raise HTTPException(status_code=400, detail="Provide 5–10 face images")

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
