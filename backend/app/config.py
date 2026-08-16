"""Конфигурация бэкенда. Всё через env — секретов в коде нет (PRD §7)."""

from __future__ import annotations

import os
from dataclasses import dataclass, field


def _env(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


def _env_int(name: str, default: int) -> int:
    raw = _env(name)
    try:
        return int(raw) if raw else default
    except ValueError:
        return default


def _env_float(name: str, default: float) -> float:
    raw = _env(name)
    try:
        return float(raw) if raw else default
    except ValueError:
        return default


def _env_bool(name: str, default: bool) -> bool:
    raw = _env(name).lower()
    if not raw:
        return default
    return raw in {"1", "true", "yes", "on"}


@dataclass(frozen=True)
class Settings:
    # --- авторизация ---
    # Токен, который приложение шлёт в Authorization: Bearer.
    api_token: str = field(default_factory=lambda: _env("PRINYAL_API_TOKEN"))

    # --- ASR ---
    # stub — детерминированная заглушка для проверки контракта;
    # gigaam — GigaAM-RNNT v2, ONNX, onnxruntime CPU (PRD §2.1).
    asr_backend: str = field(default_factory=lambda: _env("ASR_BACKEND", "stub"))
    asr_model_path: str = field(default_factory=lambda: _env("ASR_MODEL_PATH", "/models/gigaam"))
    asr_timeout_s: float = field(default_factory=lambda: _env_float("ASR_TIMEOUT_S", 20.0))

    # --- VAD ---
    # silero — Silero VAD (torch); energy — амплитудный фолбэк без тяжёлых зависимостей.
    vad_backend: str = field(default_factory=lambda: _env("VAD_BACKEND", "energy"))
    # Клипы короче этого не режем вовсе — сегментация им только вредит.
    vad_min_split_s: float = field(default_factory=lambda: _env_float("VAD_MIN_SPLIT_S", 20.0))

    # --- LLM ---
    llm_enabled: bool = field(default_factory=lambda: _env_bool("LLM_ENABLED", True))
    deepseek_api_key: str = field(default_factory=lambda: _env("DEEPSEEK_API_KEY"))
    deepseek_base_url: str = field(
        default_factory=lambda: _env("DEEPSEEK_BASE_URL", "https://api.deepseek.com")
    )
    llm_model: str = field(default_factory=lambda: _env("LLM_MODEL", "deepseek-v4-flash"))
    llm_timeout_s: float = field(default_factory=lambda: _env_float("LLM_TIMEOUT_S", 120.0))
    llm_max_tokens: int = field(default_factory=lambda: _env_int("LLM_MAX_TOKENS", 16384))
    llm_temperature: float = field(default_factory=lambda: _env_float("LLM_TEMPERATURE", 0.1))
    # Документированная особенность DeepSeek: изредка пустой content (PRD §2.2).
    llm_retries: int = field(default_factory=lambda: _env_int("LLM_RETRIES", 2))

    # --- приём ---
    max_audio_bytes: int = field(
        default_factory=lambda: _env_int("MAX_AUDIO_BYTES", 12 * 1024 * 1024)
    )
    max_audio_seconds: float = field(default_factory=lambda: _env_float("MAX_AUDIO_SECONDS", 120.0))

    # --- логи ---
    # Бэкенд stateless: по умолчанию не пишет ни аудио, ни транскриптов (PRD §2, §F-4).
    log_payloads: bool = field(default_factory=lambda: _env_bool("LOG_PAYLOADS", False))
    payload_log_dir: str = field(default_factory=lambda: _env("PAYLOAD_LOG_DIR", "/data/debug"))

    def require_token(self) -> str:
        if not self.api_token:
            raise RuntimeError(
                "PRINYAL_API_TOKEN не задан — бэкенд отказывается стартовать без авторизации"
            )
        return self.api_token


_settings: Settings | None = None


def get_settings() -> Settings:
    global _settings
    if _settings is None:
        _settings = Settings()
    return _settings


def reset_settings() -> None:
    """Только для тестов: пересобрать настройки после подмены env."""
    global _settings
    _settings = None
