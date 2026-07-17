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


class StudentUpdate(BaseModel):
    enrollment_number: str | None = Field(default=None, min_length=1, max_length=64)
    name: str | None = Field(default=None, min_length=1, max_length=255)
    batch: str | None = None


class StudentOut(BaseModel):
    id: str
    enrollment_number: str
    name: str
    batch: str | None
    crm_student_id: str | None

    model_config = {"from_attributes": True}


class StudentListItem(BaseModel):
    id: str
    enrollment_number: str
    name: str
    batch: str | None
    enrolled: bool
    image_count: int

    model_config = {"from_attributes": True}


class StudentBulkSyncIn(BaseModel):
    students: list[StudentCreate] = Field(..., min_length=1, max_length=5000)


class StudentBulkSyncOut(BaseModel):
    synced: int


class EmbeddingOut(BaseModel):
    student_id: str
    enrollment_number: str
    model_version: str
    embedding: list[float]
    image_count: int


class EnrollTemplateIn(BaseModel):
    """Phone-computed ArcFace template — no bulky face images on the server."""
    embedding: list[float] = Field(..., min_length=512, max_length=512)
    model_version: str
    image_count: int = Field(..., ge=1, le=6)
    name: str | None = None
    # Optional when using POST /students/enroll-template (collection route).
    student_id: str | None = None
    enrollment_number: str | None = None


class EnrollResponse(BaseModel):
    student_id: str
    model_version: str
    image_count: int
    embedding_dim: int


class DeviceCreate(BaseModel):
    name: str
    gate: str | None = None
    # Optional short numeric id (e.g. "1001") — easier to type on the kiosk than a UUID.
    id: str | None = Field(default=None, min_length=1, max_length=36, pattern=r"^[0-9A-Za-z_-]+$")
    token: str = Field(..., min_length=6, max_length=64)


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


class CameraIdentifyIn(BaseModel):
    embedding: list[float] = Field(..., min_length=512, max_length=512)
    model_version: str


class CameraIdentifyOut(BaseModel):
    matched: bool
    attendance_recorded: bool = False
    already_processed: bool = False
    student_id: str | None = None
    enrollment_number: str | None = None
    name: str | None = None
    score: float | None = None
    threshold: float
    message: str | None = None


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
