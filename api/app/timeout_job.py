"""Background timeout for stale PENDING verification requests."""

from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timedelta, timezone

from app.config import get_settings
from app.db import SessionLocal
from app.models import VerificationRequest, VerificationStatus

logger = logging.getLogger(__name__)
settings = get_settings()


async def timeout_loop(stop_event: asyncio.Event) -> None:
    while not stop_event.is_set():
        try:
            _expire_stale()
        except Exception:
            logger.exception("timeout job failed")
        try:
            await asyncio.wait_for(stop_event.wait(), timeout=5.0)
        except asyncio.TimeoutError:
            pass


def _expire_stale() -> None:
    cutoff = datetime.now(timezone.utc) - timedelta(seconds=settings.verification_timeout_seconds)
    db = SessionLocal()
    try:
        rows = (
            db.query(VerificationRequest)
            .filter(
                VerificationRequest.status == VerificationStatus.PENDING,
                VerificationRequest.created_at < cutoff,
            )
            .all()
        )
        now = datetime.now(timezone.utc)
        for row in rows:
            row.status = VerificationStatus.TIMEOUT
            row.resolved_at = now
        if rows:
            db.commit()
            logger.info("Marked %d requests TIMEOUT", len(rows))
    finally:
        db.close()
