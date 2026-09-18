from pathlib import Path
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """
    Application settings for BrainTwinX AI inference service.
    Values can be overridden via environment variables.
    """
    model_config = SettingsConfigDict(
        env_prefix="AI_SERVICE_",
        env_file=".env",
        extra="ignore"
    )

    APP_NAME: str = "BrainTwinX AI Service"
    ENVIRONMENT: str = "development"
    API_V1_STR: str = "/internal/ai/v1"

    # Shared secret header: X-Internal-API-Key
    # If empty, internal requests are rejected in non-test modes.
    API_KEY: str = ""

    # Preprocessing contract
    PREPROCESSING_VERSION: str = "1.0.0"
    TARGET_IMAGE_SIZE: int = 224
    NORM_MEAN: float = 0.5
    NORM_STD: float = 0.5

    # Model artifact directory & expected weight filenames
    MODEL_WEIGHTS_DIR: str = "./weights"
    CLASSIFIER_WEIGHTS_FILE: str = "classifier_cnn_v1.pt"
    SEGMENTATION_WEIGHTS_FILE: str = "segmentation_unet_v1.pt"
    LONGITUDINAL_WEIGHTS_FILE: str = "longitudinal_lstm_v1.pt"

    @property
    def weights_path(self) -> Path:
        return Path(self.MODEL_WEIGHTS_DIR).resolve()


settings = Settings()
