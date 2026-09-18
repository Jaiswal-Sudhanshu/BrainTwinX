import sys
from pathlib import Path
import pytest
from fastapi.testclient import TestClient

# Ensure app is on sys.path
ai_service_dir = Path(__file__).resolve().parent.parent
if str(ai_service_dir) not in sys.path:
    sys.path.insert(0, str(ai_service_dir))

from app.main import app
from app.config.settings import settings
from app.models.registry import model_registry


@pytest.fixture
def client():
    # Force load once
    model_registry.load_all()
    with TestClient(app) as test_client:
        yield test_client
