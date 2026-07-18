"""CRM approve / camera-punch callbacks (per tenant)."""

from __future__ import annotations

import hashlib
import hmac
import json
import logging
from datetime import datetime
from zoneinfo import ZoneInfo

import httpx
from sqlalchemy.orm import Session

from app.config import get_settings
from app.db import SessionLocal
from app.models import Device, Tenant, VerificationRequest

logger = logging.getLogger(__name__)
settings = get_settings()


def _timestamp_for_tenant(tenant: Tenant | None) -> str:
    tz_name = (tenant.timezone if tenant else None) or settings.app_timezone or "Asia/Kolkata"
    return datetime.now(ZoneInfo(tz_name)).isoformat(timespec="seconds")


def _sign(body: bytes, secret: str) -> str:
    return hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()


async def _post_signed(
    url: str,
    payload: dict,
    *,
    service_token: str,
    callback_secret: str,
) -> dict | None:
    body = json.dumps(payload, separators=(",", ":")).encode()
    headers = {
        "Content-Type": "application/json",
        "X-Face-Verify-Signature": _sign(body, callback_secret),
        "Authorization": f"Bearer {service_token}",
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


def _tenant_for_device(db: Session, device_id: str) -> Tenant | None:
    device = db.get(Device, device_id)
    if not device:
        return None
    return db.get(Tenant, device.tenant_id)


def _legacy_urls() -> tuple[str | None, str | None]:
    approve = settings.crm_callback_url or None
    punch = settings.crm_camera_punch_url
    if not punch and approve and approve.rstrip("/").endswith("/approve"):
        punch = f"{approve.rstrip('/')[: -len('/approve')]}/camera-punch"
    return approve, punch


async def notify_crm_pass(req: VerificationRequest, student_enrollment: str) -> bool:
    db = SessionLocal()
    try:
        tenant = _tenant_for_device(db, req.device_id)
        if tenant and tenant.crm_base_url and not tenant.crm_base_url.startswith("https://localhost"):
            url = tenant.approve_url
            token = tenant.service_token
            secret = tenant.callback_secret
        else:
            url, _ = _legacy_urls()
            token = settings.crm_service_token
            secret = settings.crm_callback_secret
            if not url:
                logger.error("No CRM approve URL configured for device %s", req.device_id)
                return False

        payload = {
            "request_id": req.id,
            "crm_request_id": req.crm_request_id,
            "student_id": req.student_id,
            "enrollment_number": student_enrollment,
            "device_id": req.device_id,
            "score": req.score,
            "status": "PASS",
            "timestamp": _timestamp_for_tenant(tenant),
        }
        result = await _post_signed(url, payload, service_token=token, callback_secret=secret)
        if result is None:
            return False
        logger.info("CRM callback OK for request %s → %s", req.id, url)
        return True
    finally:
        db.close()


async def notify_crm_camera_punch(
    *,
    request_id: str,
    student_id: str,
    enrollment_number: str,
    device_id: str,
    score: float,
) -> dict | None:
    db = SessionLocal()
    try:
        tenant = _tenant_for_device(db, device_id)
        if tenant and tenant.crm_base_url and not tenant.crm_base_url.startswith("https://localhost"):
            url = tenant.camera_punch_url
            token = tenant.service_token
            secret = tenant.callback_secret
        else:
            _, url = _legacy_urls()
            token = settings.crm_service_token
            secret = settings.crm_callback_secret
            if not url:
                logger.error("No CRM camera-punch URL for device %s", device_id)
                return None

        payload = {
            "request_id": request_id,
            "student_id": student_id,
            "enrollment_number": enrollment_number,
            "device_id": device_id,
            "score": score,
            "timestamp": _timestamp_for_tenant(tenant),
        }
        result = await _post_signed(url, payload, service_token=token, callback_secret=secret)
        if result is not None:
            logger.info("CRM camera punch OK for request %s → %s", request_id, url)
        return result
    finally:
        db.close()
