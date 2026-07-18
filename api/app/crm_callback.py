"""CRM approve callback on PASS."""

from __future__ import annotations

import hashlib
import hmac
import json
import logging
from datetime import datetime
from zoneinfo import ZoneInfo

import httpx

from app.config import get_settings
from app.models import VerificationRequest

logger = logging.getLogger(__name__)
settings = get_settings()


def _crm_timestamp() -> str:
    """Wall-clock for CRM punch_logs — Indian local time by default."""
    tz = ZoneInfo(settings.app_timezone or "Asia/Kolkata")
    return datetime.now(tz).isoformat(timespec="seconds")


def _sign(body: bytes) -> str:
    return hmac.new(
        settings.crm_callback_secret.encode(),
        body,
        hashlib.sha256,
    ).hexdigest()


async def _post_signed(url: str, payload: dict) -> dict | None:
    body = json.dumps(payload, separators=(",", ":")).encode()
    headers = {
        "Content-Type": "application/json",
        "X-Face-Verify-Signature": _sign(body),
        "Authorization": f"Bearer {settings.crm_service_token}",
    }
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(url, content=body, headers=headers)
            if resp.status_code >= 400:
                logger.error("CRM callback failed %s: %s", resp.status_code, resp.text)
                return None
            try:
                data = resp.json()
            except Exception:
                logger.error("CRM callback returned non-JSON body: %s", resp.text[:300])
                return None
            if not isinstance(data, dict) or data.get("ok") is not True:
                logger.error("CRM callback rejected payload: %s", data)
                return None
            return data
    except Exception as exc:
        logger.exception("CRM callback error: %s", exc)
        return None


def resolve_camera_punch_url() -> str | None:
    if settings.crm_camera_punch_url:
        return settings.crm_camera_punch_url

    base = settings.crm_callback_url.rstrip("/")
    if not base.endswith("/approve"):
        logger.error(
            "Set CRM_CAMERA_PUNCH_URL explicitly; cannot derive from %s",
            settings.crm_callback_url,
        )
        return None
    return f"{base[: -len('/approve')]}/camera-punch"


async def notify_crm_pass(req: VerificationRequest, student_enrollment: str) -> bool:
    payload = {
        "request_id": req.id,
        "crm_request_id": req.crm_request_id,
        "student_id": req.student_id,
        "enrollment_number": student_enrollment,
        "device_id": req.device_id,
        "score": req.score,
        "status": "PASS",
        "timestamp": _crm_timestamp(),
    }
    result = await _post_signed(settings.crm_callback_url, payload)
    if result is None:
        return False
    logger.info("CRM callback OK for request %s", req.id)
    return True


async def notify_crm_camera_punch(
    *,
    request_id: str,
    student_id: str,
    enrollment_number: str,
    device_id: str,
    score: float,
) -> dict | None:
    url = resolve_camera_punch_url()
    if not url:
        return None

    payload = {
        "request_id": request_id,
        "student_id": student_id,
        "enrollment_number": enrollment_number,
        "device_id": device_id,
        "score": score,
        "timestamp": _crm_timestamp(),
    }
    result = await _post_signed(url, payload)
    if result is not None:
        logger.info("CRM camera punch OK for request %s", request_id)
    return result
