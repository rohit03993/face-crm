# Face model for Android (NOT bundled in the APK)

The phone downloads this once from the Face API:

  GET {API}/models/w600k_r50.onnx

## Server setup (required for light APK)

1. Create folder on the API host:
   `api/storage/models/`  (Docker: `/app/storage/models/`)

2. Copy the ArcFace file there and name it exactly:
   `w600k_r50.onnx`  (~166 MB from InsightFace buffalo_l)

   Example on the live server:
   ```bash
   mkdir -p storage/models
   # copy w600k_r50.onnx into storage/models/
   curl -I https://face.folksindia.org/models/w600k_r50.onnx
   # expect HTTP 200
   ```

3. Redeploy / restart the API so `/models` static mount is live.

## Local Android note

`*.onnx` under `app/src/main/assets/` is ignored by the build (keeps APK small).
If the file still exists in assets for your old workflow, it will not be packaged.
