"""Seed a demo device + student for local testing.

Usage (API must be running, MySQL migrated):
  python scripts/seed_demo.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

API = "http://127.0.0.1:8000"
CRM_TOKEN = "change-me-crm-service-token"
DEVICE_TOKEN = "48291573"
DEVICE_ID = "1001"


def main() -> None:
    headers = {"Authorization": f"Bearer {CRM_TOKEN}"}
    client = httpx.Client(base_url=API, headers=headers, timeout=30.0)

    health = client.get("/health")
    health.raise_for_status()
    print("health:", health.json())

    # Create device with short numeric id (ignore if already exists)
    r = client.post(
        "/devices",
        json={
            "id": DEVICE_ID,
            "name": "Gate 1 Kiosk",
            "gate": "main",
            "token": DEVICE_TOKEN,
        },
    )
    if r.status_code == 400:
        print("device may already exist:", r.text)
        devices = client.get("/devices").json()
        device_id = next((d["id"] for d in devices if d["id"] == DEVICE_ID), None)
        device_id = device_id or (devices[0]["id"] if devices else None)
    else:
        r.raise_for_status()
        device_id = r.json()["id"]
        print("device:", r.json())

    r = client.post(
        "/students",
        json={
            "enrollment_number": "STU001",
            "name": "Demo Student",
            "batch": "MVP-Batch-1",
            "crm_student_id": "1",
        },
    )
    r.raise_for_status()
    student = r.json()
    print("student:", student)

    out = {
        "api": API,
        "crm_token": CRM_TOKEN,
        "device_id": device_id,
        "device_token": DEVICE_TOKEN,
        "student_id": student["id"],
        "enrollment_number": student["enrollment_number"],
    }
    Path(ROOT / "scripts" / "demo_credentials.json").write_text(json.dumps(out, indent=2))
    print("Wrote scripts/demo_credentials.json")
    print("Next: enroll faces via Android or POST /students/{id}/enroll with 5-10 images")


if __name__ == "__main__":
    main()
