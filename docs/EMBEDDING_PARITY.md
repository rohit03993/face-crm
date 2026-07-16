# Embedding parity (server ↔ Android)

On-device scores are only valid if **the same ArcFace model + similar alignment** is used on both sides.

## Shared model
| Side | Model |
|------|--------|
| Python API | InsightFace `buffalo_l` → recognition `w600k_r50` |
| Android | `assets/w600k_r50.onnx` (same file from buffalo_l pack) |

Preprocessing (Android ONNX):
- Aligned RGB 112×112
- `(pixel - 127.5) / 128`
- L2-normalize 512-d output

Server InsightFace recognition uses the same ArcFace head; detection/alignment differs (SCRFD vs ML Kit landmarks).

## How to measure parity
1. Start API with InsightFace weights downloaded (first enroll triggers download).
2. Capture one clear frontal face JPEG: `face.jpg`.
3. Server embedding:

```bash
cd api
python scripts/embed_image.py path/to/face.jpg > server.json
```

4. On Android (debug), run the same image through `FacePipeline.embedFromBitmap` and log the vector (or add a temporary debug button).
5. Cosine similarity between server and device vectors:
   - **> ~0.90** — excellent alignment parity
   - **0.70–0.90** — usable; tune threshold carefully
   - **< 0.70** — fix alignment / color / mirroring before go-live

Unit tests cover L2 + cosine math only (`EmbeddingMathTest`). Full parity is a device/runtime check after the ONNX asset is installed.

## Threshold
Default `MATCH_THRESHOLD=0.40` (same-identity ArcFace cosine is typically much higher after good enrollment). Tune with real students:
- Start 0.40, raise until false accepts disappear
- Enrollment quality (5–10 angles) matters more than tiny threshold tweaks
