#!/usr/bin/env python3
"""Validasi dataset foto meter nyata sebelum masuk ke repository.

Pemakaian:
  python tools/validate_field_dataset.py \
    --spec docs/samples/dataset_spec.json \
    --samples-dir docs/samples

Validator ini tidak menilai akurasi OCR. Ia memastikan setiap piksel punya jejak
provenance, kotak anotasi berada di dalam foto, dan label target dapat dinormalisasi.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path


def norm_truth(value: str) -> str | None:
    value = str(value).strip().replace(",", ".")
    if re.fullmatch(r"\d{1,7}\.\d", value):
        return value
    digits = "".join(ch for ch in value if ch.isdigit())
    return digits if 2 <= len(digits) <= 8 else None


def image_size(path: Path) -> tuple[int, int] | None:
    # Read JPEG/PNG dimensions without third-party dependencies.
    data = path.read_bytes()
    if data.startswith(b"\x89PNG\r\n\x1a\n") and len(data) >= 24:
        return int.from_bytes(data[16:20], "big"), int.from_bytes(data[20:24], "big")
    if data.startswith(b"\xff\xd8"):
        i = 2
        while i + 9 < len(data):
            if data[i] != 0xFF:
                i += 1
                continue
            marker = data[i + 1]
            i += 2
            if marker in (0xD8, 0xD9):
                continue
            if i + 2 > len(data):
                break
            length = int.from_bytes(data[i:i + 2], "big")
            if marker in range(0xC0, 0xC4) or marker in range(0xC5, 0xC8) or marker in range(0xC9, 0xCC) or marker in range(0xCD, 0xD0):
                if i + 7 <= len(data):
                    return int.from_bytes(data[i + 5:i + 7], "big"), int.from_bytes(data[i + 3:i + 5], "big")
                break
            i += length
    return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--spec", required=True, type=Path)
    parser.add_argument("--samples-dir", required=True, type=Path)
    args = parser.parse_args()
    spec = json.loads(args.spec.read_text(encoding="utf-8"))
    errors: list[str] = []
    photos = spec.get("photos", [])
    if not photos:
        errors.append("photos kosong")

    for photo_index, photo in enumerate(photos, 1):
        name = photo.get("photo", f"photo-{photo_index}")
        path = args.samples_dir / name
        if not path.is_file():
            errors.append(f"{name}: file foto tidak ditemukan")
            continue
        size = image_size(path)
        if size is None:
            errors.append(f"{name}: format gambar tidak dikenali")
            continue
        width, height = size
        required = ("machine_type", "brand_profile", "unit", "display_mode", "source_id", "light_condition", "camera_angle")
        for field in required:
            if not str(photo.get(field, "")).strip():
                errors.append(f"{name}: metadata {field} wajib diisi")
        for item_index, item in enumerate(photo.get("items", []), 1):
            box = item.get("box")
            if not isinstance(box, list) or len(box) != 4 or not all(isinstance(v, (int, float)) for v in box):
                errors.append(f"{name} item {item_index}: box harus [x0,y0,x1,y1]")
                continue
            x0, y0, x1, y1 = box
            if not (0 <= x0 < x1 <= width and 0 <= y0 < y1 <= height):
                errors.append(f"{name} item {item_index}: box di luar ukuran foto {width}x{height}")
            target = item.get("target_status") == "target"
            if target:
                label = item.get("label", "")
                if not label or norm_truth(label) != label.replace(",", "."):
                    errors.append(f"{name} item {item_index}: label target tidak valid: {label!r}")
            elif not item.get("semantic_label"):
                errors.append(f"{name} item {item_index}: semantic_label wajib untuk negatif/non-target")

    if errors:
        print("DATASET INVALID")
        print("\n".join(f"- {error}" for error in errors))
        return 1
    print(f"DATASET OK: {len(photos)} foto, {sum(len(p.get('items', [])) for p in photos)} anotasi")
    return 0


if __name__ == "__main__":
    sys.exit(main())
