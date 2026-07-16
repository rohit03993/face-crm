"""Server-side embedding parity helper.

Given a face image path, prints the L2-normalized 512-d embedding.
Use the same image on Android to compare cosine similarity (should be ~0.95+
for identical preprocessing; ML Kit vs SCRFD alignment may lower this).

  python scripts/embed_image.py path/to/face.jpg
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app.face_engine import decode_image_bytes, extract_embedding  # noqa: E402


def main() -> None:
    if len(sys.argv) < 2:
        print("Usage: python scripts/embed_image.py <image>")
        sys.exit(1)
    path = Path(sys.argv[1])
    emb = extract_embedding(decode_image_bytes(path.read_bytes()))
    print(json.dumps({"dim": len(emb), "embedding": emb.tolist()}))


if __name__ == "__main__":
    main()
