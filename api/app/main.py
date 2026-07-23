import asyncio
import logging
from contextlib import asynccontextmanager

from pathlib import Path

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles

from app import __version__
from app.config import get_settings
from app.face_engine import ensure_storage_dirs
from app.routers import auth_users, devices, platform, students, verification, ws
from app.schemas import HealthResponse
from app.timeout_job import timeout_loop

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)
settings = get_settings()


@asynccontextmanager
async def lifespan(app: FastAPI):
    ensure_storage_dirs()
    stop = asyncio.Event()
    task = asyncio.create_task(timeout_loop(stop))
    logger.info("Face Verify API starting (%s)", settings.app_env)
    yield
    stop.set()
    await task


app = FastAPI(
    title=settings.app_name,
    version=__version__,
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(students.router)
app.include_router(devices.router)
app.include_router(verification.router)
app.include_router(ws.router)
app.include_router(platform.router)
app.include_router(auth_users.router)

# Phone downloads ArcFace ONNX from here (keeps APK small).
_models_dir = Path(settings.storage_dir) / "models"
_models_dir.mkdir(parents=True, exist_ok=True)
app.mount("/models", StaticFiles(directory=str(_models_dir)), name="models")


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    return HealthResponse(
        status="ok",
        app=settings.app_name,
        model_version=settings.face_model_version,
        embedding_dim=settings.embedding_dim,
        threshold=settings.match_threshold,
    )
