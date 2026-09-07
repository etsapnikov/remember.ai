#!/usr/bin/env python3
"""Переквантовать GigaAM v3 под тот рантайм, который стоит в телефоне.

Вендорский `*.int8.onnx` не запускается: он квантует ещё и свёртки, а
onnxruntime отвечает «ConvInteger not implemented» — оператора нет ни на
десктопе 1.23, ни, судя по всему, в андроидной 1.20.

Смотрим, как сделан работающий v2: `DynamicQuantizeLinear` + `MatMulInteger`,
пятьдесят свёрток оставлены в fp32. Повторяем ровно этот рецепт — квантуем
только MatMul. Веса тяжелее вендорских, зато граф из операторов, которые
рантайм умеет наверняка.

    backend/.venv/bin/python scripts/quantize_v3.py
"""
import pathlib, shutil, sys
from onnxruntime.quantization import quantize_dynamic, QuantType

SRC = pathlib.Path("/Users/etsapnikov/Downloads/en_ru_onnx")
DST = pathlib.Path(__file__).resolve().parent.parent / "backend/models/gigaam-v3"
DST.mkdir(parents=True, exist_ok=True)

for part in ("encoder", "decoder", "joint"):
    src = SRC / f"v3_e2e_rnnt_{part}.onnx"
    dst = DST / f"{part}.onnx"
    print(f"{part}: {src.stat().st_size / 1e6:.0f} МБ → ", end="", flush=True)
    quantize_dynamic(
        str(src), str(dst),
        weight_type=QuantType.QInt8,
        # Тот же список, что у v2. Свёртки не трогаем — из-за них всё и падало.
        op_types_to_quantize=["MatMul"],
        extra_options={"MatMulConstBOnly": False},
    )
    print(f"{dst.stat().st_size / 1e6:.0f} МБ")

shutil.copy(SRC / "v3_e2e_rnnt_vocab.txt", DST / "vocab.txt")
print(f"словарь: {(DST / 'vocab.txt').stat().st_size} байт")
print(f"итого {sum(f.stat().st_size for f in DST.iterdir()) / 1e6:.0f} МБ в {DST}")
