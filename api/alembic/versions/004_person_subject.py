"""person subject student|staff

Revision ID: 004_person_subject
Revises: 003_app_users
Create Date: 2026-08-05
"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "004_person_subject"
down_revision: Union[str, None] = "003_app_users"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        "students",
        sa.Column("subject", sa.String(16), nullable=False, server_default="student"),
    )
    op.create_index("ix_students_tenant_subject", "students", ["tenant_id", "subject"])


def downgrade() -> None:
    op.drop_index("ix_students_tenant_subject", table_name="students")
    op.drop_column("students", "subject")
