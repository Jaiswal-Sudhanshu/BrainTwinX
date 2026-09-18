import secrets
from fastapi import Header, HTTPException, status
from app.config.settings import settings


def verify_internal_api_key(x_internal_api_key: str = Header(None, alias="X-Internal-API-Key")) -> str:
    """
    Guards internal endpoints against unauthenticated calls.
    If API_KEY is configured in settings, requests must supply the exact matching key.
    Uses constant-time comparison to prevent timing side-channels.
    """
    configured_key = settings.API_KEY.strip()
    if not configured_key:
        # If no key is set (e.g. initial dev without secret configured), allow with warning
        return "unauthenticated-dev"

    if not x_internal_api_key:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing required X-Internal-API-Key header",
        )

    if not secrets.compare_digest(x_internal_api_key, configured_key):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid X-Internal-API-Key header",
        )

    return x_internal_api_key
