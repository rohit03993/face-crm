"""InsightFace enrollment / embedding helpers.

Uses buffalo_l (SCRFD + ArcFace w600k_r50). The same ONNX recognition
model must be used on Android for score parity.
"""

from __future__ import annotations

import logging
from pathlib import Path
from threading import Lock

import cv2
import numpy as np

from app.config import get_settings

logger = logging.getLogger(__name__)
settings = get_settings()

_app_lock = Lock()
_face_app = None


def get_face_app():
    """Lazy-load InsightFace FaceAnalysis (heavy)."""
    global _face_app
    if _face_app is not None:
        return _face_app
    with _app_lock:
        if _face_app is not None:
            return _face_app
        from insightface.app import FaceAnalysis

        app = FaceAnalysis(
            name=settings.face_model_name,
            providers=["CPUExecutionProvider"],
        )
        app.prepare(ctx_id=-1, det_size=(640, 640))
        _face_app = app
        logger.info("Loaded InsightFace model %s", settings.face_model_name)
        return _face_app


def decode_image_bytes(data: bytes) -> np.ndarray:
    arr = np.frombuffer(data, dtype=np.uint8)
    img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    if img is None:
        raise ValueError("Could not decode image")
    return img


def extract_embedding(img_bgr: np.ndarray) -> np.ndarray:
    """Detect largest face, return L2-normalized 512-d ArcFace embedding."""
    app = get_face_app()
    faces = app.get(img_bgr)
    if not faces:
        raise ValueError("No face detected")
    face = max(faces, key=lambda f: (f.bbox[2] - f.bbox[0]) * (f.bbox[3] - f.bbox[1]))
    emb = np.asarray(face.embedding, dtype=np.float32)
    if emb.shape[0] != settings.embedding_dim:
        raise ValueError(f"Unexpected embedding dim {emb.shape[0]}")
    norm = np.linalg.norm(emb)
    if norm < 1e-6:
        raise ValueError("Zero embedding")
    return emb / norm


def average_embeddings(embeddings: list[np.ndarray]) -> np.ndarray:
    stacked = np.stack(embeddings, axis=0)
    mean = stacked.mean(axis=0)
    norm = np.linalg.norm(mean)
    if norm < 1e-6:
        raise ValueError("Zero mean embedding")
    return (mean / norm).astype(np.float32)


def cosine_similarity(a: np.ndarray, b: np.ndarray) -> float:
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-8))


def ensure_storage_dirs() -> None:
    Path(settings.faces_dir).mkdir(parents=True, exist_ok=True)
    Path(settings.fails_dir).mkdir(parents=True, exist_ok=True)
    Path(settings.storage_dir).mkdir(parents=True, exist_ok=True)
