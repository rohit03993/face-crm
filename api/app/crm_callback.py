"""CRM approve callback on PASS."""

from __future__ import annotations

import hashlib
import hmac
import logging
from datetime import datetime, timezone

import httpx

from app.config import get_settings
from app.models import VerificationRequest

logger = logging.getLogger(__name__)
settings = get_settings()


def _sign(body: bytes) -> str:
    return hmac.new(
        settings.crm_callback_secret.encode(),
        body,
        hashlib.sha256,
    ).hexdigest()


async def notify_crm_pass(req: VerificationRequest, student_enrollment: str) -> bool:
    payload = {
        "request_id": req.id,
        "crm_request_id": req.crm_request_id,
        "student_id": req.student_id,
        "enrollment_number": student_enrollment,
        "device_id": req.device_id,
        "score": req.score,
        "status": "PASS",
        "timestamp": datetime.now(timezone.utc).isoformat(),
    }
    import json

    body = json.dumps(payload, separators=(",", ":")).encode()
    headers = {
        "Content-Type": "application/json",
        "X-Face-Verify-Signature": _sign(body),
        "Authorization": f"Bearer {settings.crm_service_token}",
    }
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(settings.crm_callback_url, content=body, headers=headers)
            if resp.status_code >= 400:
                logger.error("CRM callback failed %s: %s", resp.status_code, resp.text)
                return False
            logger.info("CRM callback OK for request %s", req.id)
            return True
    except Exception as exc:
        logger.exception("CRM callback error: %s", exc)
        return False
