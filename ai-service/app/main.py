import logging
from contextlib import asynccontextmanager
from fastapi import FastAPI
from app.api.v1 import router as api_v1_router
from app.config.settings import settings
from app.models.registry import model_registry

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s"
)
logger = logging.getLogger("braintwinx-ai")


@asynccontextmanager
async def lifespan(app: FastAPI):
    """
    Application lifespan handler.
    Models are loaded ONCE per worker on startup.
    """
    logger.info("Starting BrainTwinX AI Service worker. Loading model registry...")
    model_registry.load_all()
    if model_registry.is_ready:
        logger.info("All AI models loaded successfully. Service is READY.")
    else:
        logger.warning(
            "Service started in UNREADY state. Reason: %s",
            model_registry.unready_reason
        )
    yield
    logger.info("Shutting down BrainTwinX AI Service worker.")


app = FastAPI(
    title=settings.APP_NAME,
    description="Internal inference and preprocessing service for BrainTwinX",
    version="1.0.0",
    lifespan=lifespan,
    docs_url="/internal/ai/docs",
    openapi_url="/internal/ai/openapi.json",
)

app.include_router(api_v1_router, prefix=settings.API_V1_STR)
