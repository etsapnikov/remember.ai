#!/usr/bin/env python3
"""Замер латентности конвейера (PRD §8: медиана «стоп → items» ≤ 6 с).

Бюджет ASR из §2.1 — допущение, а не факт. Этот скрипт превращает его в число.

    python scripts/measure_latency.py --url http://localhost:8080 \
        --token "$PRINYAL_API_TOKEN" --seconds 30 --runs 5
"""

from __future__ import annotations

import argparse
import json
import statistics
import subprocess
import sys
import time
import urllib.request
import uuid


def make_clip(seconds: float) -> bytes:
    """Синтетический клип нужной длины в том же контейнере, что пишет телефон."""
    cmd = [
        "ffmpeg", "-hide_banner", "-loglevel", "error",
        "-f", "lavfi",
        "-i", f"sine=frequency=190:duration={seconds}:sample_rate=16000,tremolo=f=3:d=0.8",
        "-c:a", "aac", "-b:a", "48k",
        "-movflags", "frag_keyframe+empty_moov", "-f", "mp4", "pipe:1",
    ]
    return subprocess.run(cmd, capture_output=True, check=True).stdout


def post(url: str, token: str, clip: bytes) -> tuple[dict, float]:
    boundary = "----prinyal" + uuid.uuid4().hex
    note_id = str(uuid.uuid4())

    def field(name: str, value: str) -> bytes:
        return (
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'
        ).encode()

    body = b"".join([
        field("note_id", note_id),
        field("client_ts", str(int(time.time()))),
        field("tz_offset_minutes", "180"),
        (
            f"--{boundary}\r\n"
            'Content-Disposition: form-data; name="audio"; filename="clip.m4a"\r\n'
            "Content-Type: audio/mp4\r\n\r\n"
        ).encode(),
        clip,
        f"\r\n--{boundary}--\r\n".encode(),
    ])

    request = urllib.request.Request(
        f"{url.rstrip('/')}/ingest",
        data=body,
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": f"multipart/form-data; boundary={boundary}",
        },
    )

    started = time.perf_counter()
    with urllib.request.urlopen(request, timeout=120) as response:
        payload = json.loads(response.read())
    return payload, (time.perf_counter() - started) * 1000


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://localhost:8080")
    parser.add_argument("--token", required=True)
    parser.add_argument("--seconds", type=float, default=30.0)
    parser.add_argument("--runs", type=int, default=5)
    args = parser.parse_args()

    clip = make_clip(args.seconds)
    print(f"клип {args.seconds:.0f} с, {len(clip) / 1024:.0f} КБ, прогонов: {args.runs}\n")

    asr, llm, total = [], [], []
    for run in range(1, args.runs + 1):
        try:
            payload, wall_ms = post(args.url, args.token, clip)
        except Exception as exc:  # noqa: BLE001
            print(f"  {run}: ошибка — {exc}")
            continue

        meta = payload["meta"]
        asr.append(meta["asr_ms"])
        llm.append(meta["llm_ms"])
        total.append(wall_ms)
        print(
            f"  {run}: asr {meta['asr_ms']:>5} мс · llm {meta['llm_ms']:>5} мс · "
            f"круг {wall_ms:>6.0f} мс · пунктов {len(payload['items'])}"
            + (f" · деградация {meta['degraded']}" if meta["degraded"] else "")
        )

    if not total:
        print("\nни одного успешного прогона")
        return 1

    print(
        f"\nмедианы: asr {statistics.median(asr):.0f} мс · "
        f"llm {statistics.median(llm):.0f} мс · круг {statistics.median(total):.0f} мс"
    )

    # Порог §8: медиана «стоп → items» ≤ 6 с. Круг здесь — без дороги до телефона,
    # поэтому запас должен быть, а не «ровно впритык».
    budget_ms = 6_000
    verdict = "в бюджете" if statistics.median(total) <= budget_ms else "НЕ в бюджете"
    print(f"порог §8 — {budget_ms} мс: {verdict}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
