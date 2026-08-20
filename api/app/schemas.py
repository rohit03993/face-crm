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
    # CRM may send "staff" when syncing Staff ID faces; default remains student.
    subject: str | None = Field(default=None, max_length=16)
    crm_user_id: str | None = None
    id: str | None = None


class StudentUpdate(BaseModel):
    enrollment_number: str | None = Field(default=None, min_length=1, max_length=64)
    name: str | None = Field(default=None, min_length=1, max_length=255)
    batch: str | None = None
    subject: str | None = Field(default=None, max_length=16)


class StudentOut(BaseModel):
    id: str
    enrollment_number: str
    name: str
    batch: str | None
    crm_student_id: str | None
    subject: str = "student"

    model_config = {"from_attributes": True}


class StudentListItem(BaseModel):
    id: str
    enrollment_number: str
    name: str
    batch: str | None
    enrolled: bool
    image_count: int
    has_face_photo: bool = False
    subject: str = "student"

    model_config = {"from_attributes": True}


class StudentBulkSyncIn(BaseModel):
    students: list[StudentCreate] = Field(..., min_length=1, max_length=5000)


class StudentBulkSyncOut(BaseModel):
    synced: int
    created: int = 0
    updated: int = 0
    skipped: int = 0


class StudentRemoveByEnrollmentIn(BaseModel):
    enrollment_number: str = Field(..., min_length=1, max_length=64)
    subject: str | None = Field(default="student", max_length=16)


class StudentRemoveOut(BaseModel):
    ok: bool = True
    deleted: str | None = None
    already_gone: bool = False


class StudentBulkRemoveIn(BaseModel):
    student_ids: list[str] = Field(..., min_length=1, max_length=500)


class StudentBulkRemoveOut(BaseModel):
    ok: bool = True
    deleted: int
    missing: int = 0


class SyncHealthPerson(BaseModel):
    id: str
    enrollment_number: str
    name: str
    batch: str | None = None
    subject: str = "student"
    crm_student_id: str | None = None
    enrolled: bool = False
    reason: str


class SyncHealthOut(BaseModel):
    student_count: int
    staff_count: int
    total_count: int
    missing_crm_id_count: int
    orphans: list[SyncHealthPerson]


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


class FaceCheckIn(BaseModel):
    embedding: list[float] = Field(..., min_length=512, max_length=512)
    model_version: str
    # When updating an existing student, exclude them from the duplicate search.
    exclude_student_id: str | None = None


class FaceCheckOut(BaseModel):
    duplicate: bool
    score: float | None = None
    threshold: float
    student_id: str | None = None
    enrollment_number: str | None = None
    name: str | None = None
    message: str | None = None


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
    tenant_id: str | None = None

    model_config = {"from_attributes": True}


class TenantCreateIn(BaseModel):
    name: str = Field(..., min_length=1, max_length=255)
    crm_base_url: str = Field(..., min_length=8, max_length=512)
    timezone: str = "Asia/Kolkata"
    create_device: bool = True
    device_id: str | None = Field(default=None, min_length=1, max_length=36, pattern=r"^[0-9A-Za-z_-]+$")
    device_name: str = "Gate 1"
    device_token: str | None = Field(default=None, min_length=6, max_length=64)


class TenantDeviceOut(BaseModel):
    id: str
    name: str
    token: str | None = None
    gate: str | None = None
    is_active: int | None = None
    tenant_id: str | None = None
    created_at: datetime | None = None

    model_config = {"from_attributes": True}


class TenantCreateOut(BaseModel):
    id: str
    name: str
    client_code: str
    crm_base_url: str
    service_token: str
    callback_secret: str
    timezone: str
    devices: list[TenantDeviceOut] = []


class TenantListItem(BaseModel):
    id: str
    name: str
    client_code: str
    crm_base_url: str
    timezone: str
    is_active: int
    device_count: int

    model_config = {"from_attributes": True}


class TenantDetailOut(BaseModel):
    id: str
    name: str
    client_code: str
    crm_base_url: str
    timezone: str
    is_active: int
    created_at: datetime | None = None
    devices: list[TenantDeviceOut] = []


class TenantUpdateIn(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=255)
    crm_base_url: str | None = Field(default=None, min_length=8, max_length=512)
    timezone: str | None = None
    is_active: bool | None = None


class TenantConnectIn(BaseModel):
    client_code: str = Field(..., min_length=4, max_length=32)
    crm_base_url: str = Field(..., min_length=8, max_length=512)


class TenantConnectOut(BaseModel):
    ok: bool = True
    tenant_id: str
    name: str
    client_code: str
    crm_base_url: str
    service_token: str
    callback_secret: str
    timezone: str
    devices: list[TenantDeviceOut] = []


class TenantAddDeviceIn(BaseModel):
    name: str = "Gate"
    device_id: str | None = Field(default=None, min_length=1, max_length=36, pattern=r"^[0-9A-Za-z_-]+$")
    token: str | None = Field(default=None, min_length=6, max_length=64)
    gate: str | None = None


class DeviceUpdateIn(BaseModel):
    name: str | None = Field(default=None, min_length=1, max_length=128)
    gate: str | None = None
    is_active: bool | None = None


class DeviceTokenOut(BaseModel):
    id: str
    name: str
    token: str
    message: str = "Copy this token now — it will not be shown again."


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
    marked_at: str | None = None


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


class AppUserBootstrapIn(BaseModel):
    email: str = Field(..., min_length=3, max_length=255)
    password: str = Field(..., min_length=6, max_length=128)
    name: str = Field(..., min_length=1, max_length=255)


class AppUserLoginIn(BaseModel):
    email: str = Field(..., min_length=3, max_length=255)
    password: str = Field(..., min_length=1, max_length=128)


class AppUserCreateStaffIn(BaseModel):
    email: str = Field(..., min_length=3, max_length=255)
    password: str = Field(..., min_length=6, max_length=128)
    name: str = Field(..., min_length=1, max_length=255)


class AppUserOut(BaseModel):
    id: str
    email: str
    name: str
    role: str
    is_active: bool

    model_config = {"from_attributes": True}


class AppAuthOut(BaseModel):
    user_token: str
    user: AppUserOut
    message: str | None = None
