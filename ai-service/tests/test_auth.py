import pytest
from fastapi import APIRouter, Depends
from app.api.deps import verify_internal_api_key
from app.config.settings import settings


# Dummy protected test router
test_router = APIRouter()

@test_router.get("/internal/ai/v1/test-protected", dependencies=[Depends(verify_internal_api_key)])
def protected_endpoint():
    return {"status": "authenticated"}


def test_auth_enforces_api_key(client, monkeypatch):
    from app.main import app
    app.include_router(test_router)

    # Configure a secret API key
    monkeypatch.setattr(settings, "API_KEY", "super-secret-internal-key-987")

    # 1. Missing header -> 401
    resp_missing = client.get("/internal/ai/v1/test-protected")
    assert resp_missing.status_code == 401
    assert "Missing" in resp_missing.json()["detail"]

    # 2. Wrong header -> 401
    resp_wrong = client.get(
        "/internal/ai/v1/test-protected",
        headers={"X-Internal-API-Key": "wrong-key"}
    )
    assert resp_wrong.status_code == 401
    assert "Invalid" in resp_wrong.json()["detail"]

    # 3. Valid header -> 200
    resp_valid = client.get(
        "/internal/ai/v1/test-protected",
        headers={"X-Internal-API-Key": "super-secret-internal-key-987"}
    )
    assert resp_valid.status_code == 200
    assert resp_valid.json()["status"] == "authenticated"
