from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    app_name: str = "face-verify-api"
    app_env: str = "local"
    debug: bool = True
    api_host: str = "0.0.0.0"
    api_port: int = 8000
    # Used for CRM punch timestamps (Live Punches wall-clock).
    app_timezone: str = "Asia/Kolkata"

    database_url: str = "mysql+pymysql://face:face@127.0.0.1:3307/face_verify"

    crm_service_token: str = "change-me-crm-service-token"
    device_token_pepper: str = "change-me-device-pepper"

    # Legacy single-school fallback (used only if a tenant has no CRM URL yet).
    crm_callback_url: str = "http://localhost/api/face-verify/approve"
    crm_camera_punch_url: str | None = None
    crm_callback_secret: str = "change-me-crm-callback-secret"

    # Vendor admin: create/list tenants on the Face Platform.
    # Falls back to CRM_SERVICE_TOKEN when unset (dev convenience).
    platform_admin_token: str | None = None

    face_model_name: str = "buffalo_l"
    face_model_version: str = "w600k_r50"
    embedding_dim: int = 512
    match_threshold: float = 0.40
    # Same face enrolled under two students — block if similarity >= this.
    duplicate_face_threshold: float = 0.45
    verification_timeout_seconds: int = 30
    camera_punch_cooldown_seconds: int = 60

    storage_dir: str = "./storage"
    faces_dir: str = "./storage/faces"
    fails_dir: str = "./storage/fails"


@lru_cache
def get_settings() -> Settings:
    return Settings()
