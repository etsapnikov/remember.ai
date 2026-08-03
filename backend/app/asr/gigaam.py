"""GigaAM-RNNT v2 — self-hosted ASR (PRD §2.1). Аудио не покидает свой контур.

Два пути инференса, выбираются автоматически:

* `package` — официальный пакет `gigaam` (torch, CPU). Простой и проверяемый: сам
  считает признаки и декодирует RNNT.
* `onnx` — экспортированный граф + onnxruntime. Быстрее и легче по памяти, но
  сигнатуру графа надо сверить с реальными весами — см. `docs/gigaam.md`.

Слой сознательно тонкий: контракт `Pcm → str` тот же, что у стаба, поэтому
переключение бэкенда не трогает конвейер.
"""

from __future__ import annotations

import asyncio
import os
from pathlib import Path

from ..audio import Pcm
from . import AsrError

# Пустой символ RNNT — последний в словаре у GigaAM-экспорта.
_MAX_SYMBOLS_PER_STEP = 10


class GigaAmAsr:
    name = "gigaam"

    def __init__(self, model_path: str) -> None:
        self._path = Path(model_path)
        self._impl = None
        self._mode = ""

    # --- публичный контракт ---

    async def transcribe(self, pcm: Pcm) -> str:
        impl = self._ensure_loaded()
        # torch/onnxruntime блокируют поток — уводим в пул, чтобы FastAPI не вставал.
        try:
            return await asyncio.to_thread(impl, pcm)
        except AsrError:
            raise
        except Exception as exc:  # noqa: BLE001
            raise AsrError(f"GigaAM не смог распознать: {exc}") from exc

    def warmup(self) -> None:
        """Прогрев на старте контейнера: первый реальный запрос не должен платить
        за загрузку весов (это исказило бы замер латентности из §8)."""
        impl = self._ensure_loaded()
        silence = Pcm(_zeros(16_000))
        try:
            impl(silence)
        except Exception:  # noqa: BLE001 - прогрев не обязан быть успешным
            pass

    @property
    def mode(self) -> str:
        return self._mode

    # --- выбор реализации ---

    def _ensure_loaded(self):
        if self._impl is not None:
            return self._impl

        preferred = os.environ.get("ASR_GIGAAM_MODE", "auto")
        errors: list[str] = []

        if preferred in {"auto", "package"}:
            try:
                self._impl = self._load_package()
                self._mode = "package"
                return self._impl
            except Exception as exc:  # noqa: BLE001
                errors.append(f"package: {exc}")

        if preferred in {"auto", "onnx"}:
            try:
                self._impl = self._load_onnx()
                self._mode = "onnx"
                return self._impl
            except Exception as exc:  # noqa: BLE001
                errors.append(f"onnx: {exc}")

        raise AsrError("GigaAM недоступен — " + "; ".join(errors))

    def _load_package(self):
        import gigaam  # noqa: PLC0415
        import torch  # noqa: PLC0415

        model_name = os.environ.get("ASR_GIGAAM_MODEL", "v2_rnnt")
        if self._path.is_dir():
            os.environ.setdefault("GIGAAM_MODEL_DIR", str(self._path))
        model = gigaam.load_model(model_name, device="cpu")

        def run(pcm: Pcm) -> str:
            wav = torch.tensor([s / 32768.0 for s in pcm.samples], dtype=torch.float32)
            with torch.inference_mode():
                text = model.transcribe_sample(wav.unsqueeze(0), pcm.sample_rate)
            return _clean(text)

        return run

    def _load_onnx(self):
        import numpy as np  # noqa: PLC0415
        import onnxruntime as ort  # noqa: PLC0415

        encoder_path = _pick(self._path, "encoder")
        decoder_path = _pick(self._path, "decoder")
        joint_path = _pick(self._path, "joint")
        vocab = _read_vocab(self._path)

        opts = ort.SessionOptions()
        opts.intra_op_num_threads = int(os.environ.get("ASR_THREADS", "0")) or 0
        providers = ["CPUExecutionProvider"]
        encoder = ort.InferenceSession(str(encoder_path), opts, providers=providers)
        decoder = ort.InferenceSession(str(decoder_path), opts, providers=providers)
        joint = ort.InferenceSession(str(joint_path), opts, providers=providers)

        _assert_signature(encoder, ("audio_signal", "length"), "encoder")
        features = _build_feature_extractor()
        blank = len(vocab)

        def run(pcm: Pcm) -> str:
            feats = features(pcm)  # (1, n_mels, frames), float32
            length = np.array([feats.shape[2]], dtype=np.int64)
            enc_out, enc_len = encoder.run(None, {"audio_signal": feats, "length": length})
            tokens = _greedy_rnnt(decoder, joint, enc_out, int(enc_len[0]), blank, np)
            return _clean("".join(vocab[t] for t in tokens).replace("▁", " "))

        return run


# --- вспомогательное ---


def _zeros(n: int) -> "object":
    import array  # noqa: PLC0415

    return array.array("h", [0]) * n


def _pick(root: Path, part: str) -> Path:
    if not root.is_dir():
        raise AsrError(f"нет каталога с весами: {root}")
    matches = sorted(p for p in root.glob("*.onnx") if part in p.name.lower())
    if not matches:
        raise AsrError(f"в {root} не найден {part}*.onnx")
    return matches[0]


def _read_vocab(root: Path) -> list[str]:
    for name in ("tokens.txt", "vocab.txt", "tokenizer.vocab"):
        candidate = root / name
        if candidate.exists():
            lines = candidate.read_text("utf-8").splitlines()
            # Формат «токен<TAB>id» тоже встречается — берём первую колонку.
            return [line.split("\t")[0] for line in lines if line]
    raise AsrError(f"в {root} нет словаря токенов (tokens.txt)")


def _assert_signature(session, expected: tuple[str, ...], what: str) -> None:
    names = tuple(i.name for i in session.get_inputs())
    if names[: len(expected)] != expected:
        raise AsrError(
            f"сигнатура {what} не та, что ожидается: {names} вместо {expected}. "
            "Сверьте экспорт с docs/gigaam.md"
        )


def _build_feature_extractor():
    """Признаки считает torchaudio по конфигу GigaAM: расхождение в мел-фильтрах
    ломает распознавание тише, чем падение, — поэтому конфиг живёт в одном месте."""
    import numpy as np  # noqa: PLC0415
    import torch  # noqa: PLC0415
    import torchaudio  # noqa: PLC0415

    n_mels = int(os.environ.get("ASR_N_MELS", "64"))
    mel = torchaudio.transforms.MelSpectrogram(
        sample_rate=16_000,
        n_fft=400,
        win_length=400,
        hop_length=160,
        n_mels=n_mels,
        power=2.0,
    )

    def extract(pcm: Pcm):
        wav = torch.tensor([s / 32768.0 for s in pcm.samples], dtype=torch.float32)
        spec = mel(wav.unsqueeze(0))
        logmel = torch.log(spec.clamp(min=1e-9))
        return logmel.numpy().astype(np.float32)

    return extract


def _greedy_rnnt(decoder, joint, enc_out, enc_len: int, blank: int, np) -> list[int]:
    """Стандартный жадный RNNT-декод: на каждом кадре энкодера тянем символы, пока
    не выпадет blank или не упрёмся в потолок символов на кадр."""
    hidden = None
    tokens: list[int] = []
    last = np.array([[blank]], dtype=np.int32)

    # enc_out обычно (B, D, T) — приводим к (T, D) один раз.
    frames = enc_out[0].T if enc_out.shape[1] != enc_len else enc_out[0]

    for t in range(min(enc_len, frames.shape[0])):
        frame = frames[t][None, :, None]
        for _ in range(_MAX_SYMBOLS_PER_STEP):
            dec_inputs = {"targets": last, "target_length": np.array([1], dtype=np.int32)}
            if hidden is not None:
                dec_inputs["states"] = hidden
            dec_result = decoder.run(None, dec_inputs)
            dec_out, hidden = dec_result[0], dec_result[1:] or None
            logits = joint.run(None, {"encoder_outputs": frame, "decoder_outputs": dec_out})[0]
            token = int(np.argmax(logits.reshape(-1)))
            if token == blank:
                break
            tokens.append(token)
            last = np.array([[token]], dtype=np.int32)

    return tokens


def _clean(text: str) -> str:
    # Приводим к тому же виду, что и стаб: нижний регистр, одиночные пробелы.
    return " ".join(text.lower().split())
