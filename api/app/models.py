import enum
import uuid
from datetime import datetime

from sqlalchemy import (
    JSON,
    DateTime,
    Enum,
    Float,
    ForeignKey,
    Integer,
    String,
    Text,
    UniqueConstraint,
    func,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db import Base


def _uuid() -> str:
    return str(uuid.uuid4())


class VerificationStatus(str, enum.Enum):
    PENDING = "PENDING"
    PASS = "PASS"
    FAIL = "FAIL"
    TIMEOUT = "TIMEOUT"


class Student(Base):
    __tablename__ = "students"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    enrollment_number: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    name: Mapped[str] = mapped_column(String(255))
    batch: Mapped[str | None] = mapped_column(String(128), nullable=True)
    crm_student_id: Mapped[str | None] = mapped_column(String(64), nullable=True, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )

    templates: Mapped[list["FaceTemplate"]] = relationship(back_populates="student")
    images: Mapped[list["FaceImage"]] = relationship(back_populates="student")


class FaceTemplate(Base):
    __tablename__ = "face_templates"
    __table_args__ = (UniqueConstraint("student_id", "model_version", name="uq_student_model"),)

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    student_id: Mapped[str] = mapped_column(String(36), ForeignKey("students.id"), index=True)
    embedding: Mapped[list] = mapped_column(JSON)  # list[float] length 512
    model_version: Mapped[str] = mapped_column(String(64))
    image_count: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )

    student: Mapped["Student"] = relationship(back_populates="templates")


class FaceImage(Base):
    __tablename__ = "face_images"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    student_id: Mapped[str] = mapped_column(String(36), ForeignKey("students.id"), index=True)
    path: Mapped[str] = mapped_column(String(512))
    angle: Mapped[str | None] = mapped_column(String(64), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())

    student: Mapped["Student"] = relationship(back_populates="images")


class Device(Base):
    __tablename__ = "devices"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    name: Mapped[str] = mapped_column(String(128))
    gate: Mapped[str | None] = mapped_column(String(128), nullable=True)
    token_hash: Mapped[str] = mapped_column(String(128), unique=True)
    is_active: Mapped[int] = mapped_column(Integer, default=1)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class VerificationRequest(Base):
    __tablename__ = "verification_requests"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    student_id: Mapped[str] = mapped_column(String(36), ForeignKey("students.id"), index=True)
    device_id: Mapped[str] = mapped_column(String(36), ForeignKey("devices.id"), index=True)
    status: Mapped[VerificationStatus] = mapped_column(
        Enum(VerificationStatus), default=VerificationStatus.PENDING, index=True
    )
    score: Mapped[float | None] = mapped_column(Float, nullable=True)
    crm_request_id: Mapped[str | None] = mapped_column(String(64), nullable=True, index=True)
    meta: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    resolved_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    student: Mapped["Student"] = relationship()
    device: Mapped["Device"] = relationship()
    fail_captures: Mapped[list["FailCapture"]] = relationship(back_populates="request")


class FailCapture(Base):
    __tablename__ = "fail_captures"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    request_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("verification_requests.id"), index=True
    )
    image_path: Mapped[str] = mapped_column(String(512))
    note: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())

    request: Mapped["VerificationRequest"] = relationship(back_populates="fail_captures")
