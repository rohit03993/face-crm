"""In-memory WebSocket hub for kiosk devices."""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from fastapi import WebSocket

logger = logging.getLogger(__name__)


class KioskHub:
    def __init__(self) -> None:
        self._connections: dict[str, WebSocket] = {}
        self._lock = asyncio.Lock()

    async def connect(self, device_id: str, ws: WebSocket) -> None:
        await ws.accept()
        async with self._lock:
            old = self._connections.get(device_id)
            self._connections[device_id] = ws
        if old is not None:
            try:
                await old.close(code=4000, reason="replaced")
            except Exception:
                pass
        logger.info("Kiosk connected: %s", device_id)

    async def disconnect(self, device_id: str, ws: WebSocket) -> None:
        async with self._lock:
            if self._connections.get(device_id) is ws:
                del self._connections[device_id]
        logger.info("Kiosk disconnected: %s", device_id)

    def is_online(self, device_id: str) -> bool:
        return device_id in self._connections

    async def send_json(self, device_id: str, payload: dict[str, Any]) -> bool:
        async with self._lock:
            ws = self._connections.get(device_id)
        if ws is None:
            return False
        try:
            await ws.send_json(payload)
            return True
        except Exception as exc:
            logger.warning("WS send failed for %s: %s", device_id, exc)
            async with self._lock:
                if self._connections.get(device_id) is ws:
                    del self._connections[device_id]
            return False


hub = KioskHub()
