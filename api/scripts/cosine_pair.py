"""Cosine similarity between two embedding JSON files from embed_image.py."""

from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np


def load(path: Path) -> np.ndarray:
    data = json.loads(path.read_text())
    emb = np.asarray(data["embedding"] if isinstance(data, dict) else data, dtype=np.float32)
    return emb / (np.linalg.norm(emb) + 1e-8)


def main() -> None:
    if len(sys.argv) < 3:
        print("Usage: python scripts/cosine_pair.py server.json device.json")
        sys.exit(1)
    a = load(Path(sys.argv[1]))
    b = load(Path(sys.argv[2]))
    if a.shape != b.shape:
        print("dim mismatch", a.shape, b.shape)
        sys.exit(2)
    print(float(np.dot(a, b)))


if __name__ == "__main__":
    main()
