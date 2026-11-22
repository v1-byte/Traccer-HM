#!/usr/bin/env python3
"""Bangun dataset OCR (format ekspor aplikasi) dari foto lapangan beranotasi di docs/samples/dataset_spec.json.

  python tools/make_dataset.py build            -> docs/samples/dataset/{images/,labels.csv,dataset_manifest.json}
  python tools/make_dataset.py build --zip X.zip -> sekaligus zip yang bisa langsung dipakai tools/train_digits.py
  python tools/make_dataset.py check            -> cek: label cocok dengan aturan aplikasi, tidak ada coretan merah di potongan target

Satu foto -> satu sampel target (label = angka meter) + contoh gangguan (angka speedometer, tanggal, logo) dari foto yang
sama. Potongan target harus bersih: coretan lingkaran di foto acuan harus berada di LUAR kotak target.
Kolom dan nilai mengikuti Trainer.exportTo / DatasetCatalog.kt, jadi hasilnya satu format dengan ekspor aplikasi.
"""
import argparse, csv, json, os, re, sys, zipfile
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
SAMPLES = os.path.join(HERE, "..", "docs", "samples")
SPEC = os.path.join(SAMPLES, "dataset_spec.json")
OUT = os.path.join(SAMPLES, "dataset")
FIELDS = ["id", "file", "label", "exact_text", "semantic_label", "target_status", "region_type", "unit", "unit_name",
          "machine_type", "brand_profile", "display_mode", "source_kind", "source_id", "model_context", "glare", "blur",
          "occlusion", "crop", "brightness", "notes", "learn_source", "vehicle_model", "unit_year", "country_market",
          "light_condition", "glare_level", "camera_angle", "display_state"]


def norm_truth(s):
    """Cermin normTruth/normalizeCandidate (Validation.kt): digit saja (2-8) atau d.d dengan satu digit desimal."""
    t = s.strip().replace(",", ".")
    if re.fullmatch(r"\d{1,7}[.]\d", t):
        return t
    d = "".join(c for c in t if c.isdigit())
    return d if 2 <= len(d) <= 8 else None


def red_px(a):
    r, g, b = a[..., 0], a[..., 1], a[..., 2]
    return (r >= 130) & (r - g >= 45) & (r - b >= 60) & (g >= b - 10)


def rows():
    spec = json.load(open(SPEC))
    n = 0
    for ph in spec["photos"]:
        img = Image.open(os.path.join(SAMPLES, ph["photo"])).convert("RGB")
        for it in ph["items"]:
            n += 1
            neg = it["target_status"] != "target"
            crop = img.crop(tuple(it["box"]))
            if max(crop.size) > 640:
                k = 640 / crop.width; crop = crop.resize((640, max(8, int(crop.height * k))), Image.LANCZOS)
            yield n, ph, it, neg, crop


def build(a):
    os.makedirs(os.path.join(OUT, "images"), exist_ok=True)
    for f in os.listdir(os.path.join(OUT, "images")):
        os.remove(os.path.join(OUT, "images", f))
    lines = []
    for n, ph, it, neg, crop in rows():
        label = "" if neg else it["label"]
        exact = it.get("text", "") if neg else it["label"]
        fn = f"images/{n:04d}.jpg"; crop.save(os.path.join(OUT, fn), quality=92)
        r = [n, fn, label, exact, it["semantic_label"], it["target_status"], it["region_type"], ph["unit"], ph["unit_name"],
             ph["machine_type"], ph["brand_profile"], ph["display_mode"], "field_photo", ph.get("source_id", ""),
             ph.get("model_context", ""), False, False, False, it.get("crop", "full"), ph.get("brightness", "normal"),
             it.get("notes", ""), "manual", "", "", "", ph.get("light_condition", ""), "none", ph.get("camera_angle", ""), "normal"]
        lines.append([str(x).replace(",", " ").replace("\n", " ") for x in r])
    with open(os.path.join(OUT, "labels.csv"), "w", newline="") as f:
        f.write(",".join(FIELDS) + "\n")
        for l in lines: f.write(",".join(l) + "\n")
    json.dump({"schema": 1, "purpose": "Sampel lapangan beranotasi manual (docs/samples/dataset_spec.json); piksel dari foto asli",
               "fields": FIELDS, "note": "sources/terms tidak disertakan; lihat dataset_manifest.json hasil ekspor aplikasi"},
              open(os.path.join(OUT, "dataset_manifest.json"), "w"), indent=2, ensure_ascii=False)
    print(f"{len(lines)} baris -> {OUT}")
    if a.zip:
        with zipfile.ZipFile(a.zip, "w", zipfile.ZIP_DEFLATED) as z:
            for root, _, fs in os.walk(OUT):
                for f in fs:
                    p = os.path.join(root, f); z.write(p, os.path.relpath(p, OUT))
        print("zip:", a.zip)


def check(_):
    bad = 0
    for n, ph, it, neg, crop in rows():
        if neg:
            continue
        t = norm_truth(it["label"])
        red = int(red_px(np.array(crop).astype(int)).sum())
        ok = t == it["label"] and red < 20
        bad += not ok
        print(f"{n:04d} label={it['label']} normTruth={t} piksel merah di potongan={red} {'OK' if ok else 'GAGAL'}")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sp = p.add_subparsers(dest="cmd", required=True)
    b = sp.add_parser("build"); b.add_argument("--zip"); b.set_defaults(f=build)
    sp.add_parser("check").set_defaults(f=check)
    a = p.parse_args(); a.f(a)
