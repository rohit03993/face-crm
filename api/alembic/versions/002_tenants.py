"""multi-tenant schools

Revision ID: 002_tenants
Revises: 001_initial
Create Date: 2026-07-18
"""

from typing import Sequence, Union

import secrets
import string

import sqlalchemy as sa
from alembic import op

revision: str = "002_tenants"
down_revision: Union[str, None] = "001_initial"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def _client_code() -> str:
    alphabet = string.ascii_uppercase + string.digits
    body = "".join(secrets.choice(alphabet) for _ in range(6))
    return f"TB-{body}"


def upgrade() -> None:
    op.create_table(
        "tenants",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("name", sa.String(255), nullable=False),
        sa.Column("client_code", sa.String(32), nullable=False),
        sa.Column("crm_base_url", sa.String(512), nullable=False),
        sa.Column("service_token", sa.String(128), nullable=False),
        sa.Column("callback_secret", sa.String(128), nullable=False),
        sa.Column("timezone", sa.String(64), nullable=False, server_default="Asia/Kolkata"),
        sa.Column("is_active", sa.Integer(), nullable=False, server_default="1"),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
    )
    op.create_index("ix_tenants_client_code", "tenants", ["client_code"], unique=True)
    op.create_index("ix_tenants_service_token", "tenants", ["service_token"], unique=True)

    # Default tenant so existing Folks installs keep working after migrate.
    conn = op.get_bind()
    default_id = "00000000-0000-0000-0000-000000000001"
    code = _client_code()
    service_token = secrets.token_urlsafe(32)
    callback_secret = secrets.token_urlsafe(32)
    conn.execute(
        sa.text(
            """
            INSERT INTO tenants (id, name, client_code, crm_base_url, service_token, callback_secret, timezone, is_active)
            VALUES (:id, :name, :code, :url, :token, :secret, 'Asia/Kolkata', 1)
            """
        ),
        {
            "id": default_id,
            "name": "Default school",
            "code": code,
            "url": "https://localhost",
            "token": service_token,
            "secret": callback_secret,
        },
    )

    op.add_column("students", sa.Column("tenant_id", sa.String(36), nullable=True))
    op.add_column("devices", sa.Column("tenant_id", sa.String(36), nullable=True))

    conn.execute(sa.text("UPDATE students SET tenant_id = :tid"), {"tid": default_id})
    conn.execute(sa.text("UPDATE devices SET tenant_id = :tid"), {"tid": default_id})

    op.alter_column("students", "tenant_id", existing_type=sa.String(36), nullable=False)
    op.alter_column("devices", "tenant_id", existing_type=sa.String(36), nullable=False)

    op.create_foreign_key("fk_students_tenant", "students", "tenants", ["tenant_id"], ["id"])
    op.create_foreign_key("fk_devices_tenant", "devices", "tenants", ["tenant_id"], ["id"])
    op.create_index("ix_students_tenant_id", "students", ["tenant_id"])
    op.create_index("ix_devices_tenant_id", "devices", ["tenant_id"])

    # enrollment unique per school, not globally
    op.drop_index("ix_students_enrollment_number", table_name="students")
    op.create_index(
        "ix_students_tenant_enrollment",
        "students",
        ["tenant_id", "enrollment_number"],
        unique=True,
    )


def downgrade() -> None:
    op.drop_index("ix_students_tenant_enrollment", table_name="students")
    op.create_index("ix_students_enrollment_number", "students", ["enrollment_number"], unique=True)
    op.drop_constraint("fk_devices_tenant", "devices", type_="foreignkey")
    op.drop_constraint("fk_students_tenant", "students", type_="foreignkey")
    op.drop_index("ix_devices_tenant_id", table_name="devices")
    op.drop_index("ix_students_tenant_id", table_name="students")
    op.drop_column("devices", "tenant_id")
    op.drop_column("students", "tenant_id")
    op.drop_index("ix_tenants_service_token", table_name="tenants")
    op.drop_index("ix_tenants_client_code", table_name="tenants")
    op.drop_table("tenants")
