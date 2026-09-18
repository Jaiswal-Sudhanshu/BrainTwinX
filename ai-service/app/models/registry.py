import logging
from pathlib import Path
from typing import Any, Dict, Optional
import torch

from app.config.settings import settings

logger = logging.getLogger(__name__)


class ModelRegistry:
    """
    Model Registry abstraction managing lifecycle, loading, and readiness of inference models.
    Models are loaded ONCE per worker at startup.
    Fails closed when weights are missing or corrupted.
    """

    def __init__(self, weights_dir: Optional[Path] = None):
        self.weights_dir = weights_dir or settings.weights_path
        self._models: Dict[str, Any] = {}
        self._loaded_status: Dict[str, bool] = {
            "classifier": False,
            "segmentation": False,
            "longitudinal": False,
        }
        self._unready_reasons: list[str] = []

    def load_all(self) -> None:
        """
        Loads models once on application startup.
        """
        logger.info("Initializing Model Registry from directory: %s", self.weights_dir)
        self.weights_dir.mkdir(parents=True, exist_ok=True)
        self._unready_reasons.clear()

        self._load_model("classifier", settings.CLASSIFIER_WEIGHTS_FILE)
        self._load_model("segmentation", settings.SEGMENTATION_WEIGHTS_FILE)
        self._load_model("longitudinal", settings.LONGITUDINAL_WEIGHTS_FILE)

    def _load_model(self, model_key: str, filename: str) -> None:
        file_path = self.weights_dir / filename
        if not file_path.exists():
            reason = f"{model_key.upper()}_WEIGHTS_NOT_FOUND: {filename}"
            logger.warning("Model weight file not found: %s", file_path)
            self._loaded_status[model_key] = False
            self._unready_reasons.append(reason)
            return

        try:
            if model_key == "classifier":
                from app.models.classifier import load_classifier_weights
                model = load_classifier_weights(file_path)
                self._models[model_key] = model
            elif model_key in ("segmenter", "segmentation"):
                from app.models.segmenter import load_segmenter_weights
                model = load_segmenter_weights(file_path)
                self._models["segmenter"] = model
                self._models["segmentation"] = model
            elif model_key in ("forecaster", "longitudinal"):
                from app.models.forecaster import load_forecaster_weights
                model = load_forecaster_weights(file_path)
                self._models["forecaster"] = model
                self._models["longitudinal"] = model
            else:
                checkpoint = torch.load(file_path, map_location="cpu", weights_only=True)
                self._models[model_key] = checkpoint

            self._loaded_status[model_key] = True
            logger.info("Successfully loaded model weights for '%s'", model_key)
        except Exception as e:
            reason = f"{model_key.upper()}_LOAD_FAILED: {str(e)}"
            logger.error("Failed to load weights for '%s' from %s: %s", model_key, file_path, e)
            self._loaded_status[model_key] = False
            self._unready_reasons.append(reason)

    @property
    def is_ready(self) -> bool:
        """
        Ready only if all models are loaded and validated.
        """
        return all(self._loaded_status.values())

    @property
    def loaded_status(self) -> Dict[str, bool]:
        return dict(self._loaded_status)

    @property
    def unready_reason(self) -> Optional[str]:
        if self.is_ready:
            return None
        return "; ".join(self._unready_reasons) if self._unready_reasons else "MODELS_NOT_INITIALIZED"

    def get_model(self, model_key: str) -> Optional[Any]:
        if model_key in ("segmenter", "segmentation"):
            return self._models.get("segmenter") or self._models.get("segmentation")
        if model_key in ("forecaster", "longitudinal"):
            return self._models.get("forecaster") or self._models.get("longitudinal")
        return self._models.get(model_key)


model_registry = ModelRegistry()
