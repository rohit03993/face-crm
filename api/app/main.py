import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app import __version__
from app.config import get_settings
from app.face_engine import ensure_storage_dirs
from app.routers import devices, students, verification, ws
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


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    return HealthResponse(
        status="ok",
        app=settings.app_name,
        model_version=settings.face_model_version,
        embedding_dim=settings.embedding_dim,
        threshold=settings.match_threshold,
    )
