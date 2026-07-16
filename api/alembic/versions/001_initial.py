"""initial schema

Revision ID: 001_initial
Revises:
Create Date: 2026-07-17
"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "001_initial"
down_revision: Union[str, None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "students",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("enrollment_number", sa.String(64), nullable=False),
        sa.Column("name", sa.String(255), nullable=False),
        sa.Column("batch", sa.String(128), nullable=True),
        sa.Column("crm_student_id", sa.String(64), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
    )
    op.create_index("ix_students_enrollment_number", "students", ["enrollment_number"], unique=True)
    op.create_index("ix_students_crm_student_id", "students", ["crm_student_id"])

    op.create_table(
        "devices",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("name", sa.String(128), nullable=False),
        sa.Column("gate", sa.String(128), nullable=True),
        sa.Column("token_hash", sa.String(128), nullable=False),
        sa.Column("is_active", sa.Integer(), nullable=False, server_default="1"),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
    )
    op.create_index("ix_devices_token_hash", "devices", ["token_hash"], unique=True)

    op.create_table(
        "face_templates",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("student_id", sa.String(36), sa.ForeignKey("students.id"), nullable=False),
        sa.Column("embedding", sa.JSON(), nullable=False),
        sa.Column("model_version", sa.String(64), nullable=False),
        sa.Column("image_count", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.UniqueConstraint("student_id", "model_version", name="uq_student_model"),
    )
    op.create_index("ix_face_templates_student_id", "face_templates", ["student_id"])

    op.create_table(
        "face_images",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("student_id", sa.String(36), sa.ForeignKey("students.id"), nullable=False),
        sa.Column("path", sa.String(512), nullable=False),
        sa.Column("angle", sa.String(64), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
    )
    op.create_index("ix_face_images_student_id", "face_images", ["student_id"])

    op.create_table(
        "verification_requests",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("student_id", sa.String(36), sa.ForeignKey("students.id"), nullable=False),
        sa.Column("device_id", sa.String(36), sa.ForeignKey("devices.id"), nullable=False),
        sa.Column(
            "status",
            sa.Enum("PENDING", "PASS", "FAIL", "TIMEOUT", name="verificationstatus"),
            nullable=False,
        ),
        sa.Column("score", sa.Float(), nullable=True),
        sa.Column("crm_request_id", sa.String(64), nullable=True),
        sa.Column("meta", sa.JSON(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column("resolved_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.create_index("ix_verification_requests_student_id", "verification_requests", ["student_id"])
    op.create_index("ix_verification_requests_device_id", "verification_requests", ["device_id"])
    op.create_index("ix_verification_requests_status", "verification_requests", ["status"])
    op.create_index("ix_verification_requests_crm_request_id", "verification_requests", ["crm_request_id"])

    op.create_table(
        "fail_captures",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column(
            "request_id",
            sa.String(36),
            sa.ForeignKey("verification_requests.id"),
            nullable=False,
        ),
        sa.Column("image_path", sa.String(512), nullable=False),
        sa.Column("note", sa.Text(), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
    )
    op.create_index("ix_fail_captures_request_id", "fail_captures", ["request_id"])


def downgrade() -> None:
    op.drop_table("fail_captures")
    op.drop_table("verification_requests")
    op.drop_table("face_images")
    op.drop_table("face_templates")
    op.drop_table("devices")
    op.drop_table("students")
