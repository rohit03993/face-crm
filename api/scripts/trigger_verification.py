"""Trigger a verification request as CRM would after RFID tap.

Requires enrolled student + online kiosk.
  python scripts/trigger_verification.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[1]
creds_path = ROOT / "scripts" / "demo_credentials.json"
if not creds_path.exists():
    print("Run seed_demo.py first")
    sys.exit(1)

creds = json.loads(creds_path.read_text())
client = httpx.Client(
    base_url=creds["api"],
    headers={"Authorization": f"Bearer {creds['crm_token']}"},
    timeout=30.0,
)
r = client.post(
    "/verification-requests",
    json={
        "student_id": creds["student_id"],
        "device_id": creds["device_id"],
        "crm_request_id": "crm-demo-1",
        "meta": {"source": "mock_rfid"},
    },
)
print(r.status_code, r.text)
