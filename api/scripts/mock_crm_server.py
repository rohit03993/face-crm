"""Mock CRM callback receiver for local E2E dry runs.

Run: python scripts/mock_crm_server.py
Listens on http://127.0.0.1:9999/api/face-verify/approve
"""

from __future__ import annotations

import hashlib
import hmac
import json
from http.server import BaseHTTPRequestHandler, HTTPServer

SECRET = "change-me-crm-callback-secret"


class Handler(BaseHTTPRequestHandler):
    def do_POST(self) -> None:  # noqa: N802
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)
        sig = self.headers.get("X-Face-Verify-Signature", "")
        expected = hmac.new(SECRET.encode(), body, hashlib.sha256).hexdigest()
        ok = hmac.compare_digest(sig, expected)
        print("--- CRM approve callback ---")
        print("signature_ok:", ok)
        try:
            print(json.dumps(json.loads(body), indent=2))
        except json.JSONDecodeError:
            print(body)
        self.send_response(200 if ok else 401)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"ok":true}' if ok else b'{"ok":false}')

    def log_message(self, fmt: str, *args) -> None:
        return


if __name__ == "__main__":
    server = HTTPServer(("127.0.0.1", 9999), Handler)
    print("Mock CRM listening on http://127.0.0.1:9999")
    server.serve_forever()
