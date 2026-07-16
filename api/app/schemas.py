from datetime import datetime
from typing import Any

from pydantic import BaseModel, Field


class HealthResponse(BaseModel):
    status: str
    app: str
    model_version: str
    embedding_dim: int
    threshold: float


class StudentCreate(BaseModel):
    enrollment_number: str = Field(..., min_length=1, max_length=64)
    name: str = Field(..., min_length=1, max_length=255)
    batch: str | None = None
    crm_student_id: str | None = None
    id: str | None = None


class StudentOut(BaseModel):
    id: str
    enrollment_number: str
    name: str
    batch: str | None
    crm_student_id: str | None

    model_config = {"from_attributes": True}


class EmbeddingOut(BaseModel):
    student_id: str
    enrollment_number: str
    model_version: str
    embedding: list[float]
    image_count: int


class EnrollResponse(BaseModel):
    student_id: str
    model_version: str
    image_count: int
    embedding_dim: int


class DeviceCreate(BaseModel):
    name: str
    gate: str | None = None
    token: str = Field(..., min_length=8)


class DeviceOut(BaseModel):
    id: str
    name: str
    gate: str | None
    is_active: int

    model_config = {"from_attributes": True}


class VerificationRequestCreate(BaseModel):
    student_id: str | None = None
    enrollment_number: str | None = None
    device_id: str
    crm_request_id: str | None = None
    meta: dict[str, Any] | None = None


class VerificationRequestOut(BaseModel):
    id: str
    student_id: str
    device_id: str
    status: str
    score: float | None
    crm_request_id: str | None
    created_at: datetime
    resolved_at: datetime | None

    model_config = {"from_attributes": True}


class VerificationResultIn(BaseModel):
    request_id: str
    score: float
    passed: bool
    note: str | None = None


class WsVerificationPayload(BaseModel):
    type: str = "verification_request"
    request_id: str
    student_id: str
    enrollment_number: str
    name: str
    model_version: str
    embedding: list[float]
    threshold: float
    timeout_seconds: int
