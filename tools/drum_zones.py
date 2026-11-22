#!/usr/bin/env python3
"""Ekstrak zona meter drum dari foto acuan yang dicoret tiga warna, lalu simpan sebagai dataset.

Konvensi coretan (lihat docs/samples/README.md):
  merah  = drum gelap yang dihitung sebagai HM        -> zona "counted"
  hijau  = penanda "1/10" (posisi koma)                -> zona "marker"
  kuning = drum 1/10 jam, latar putih + angka merah    -> zona "tenth"

Pakai:
  python tools/drum_zones.py add FOTO.jpg --counted 00991 --tenth 2 [--unit "Jd 08"]
      -> menambah satu baris ke docs/samples/drum_zones.json (kotak zona ternormalisasi 0..1) dan,
         bila ada OpenCV, menulis FOTO_clean_approx.jpg (coretan dihapus dengan inpaint; PENDEKATAN, bukan foto asli)
  python tools/drum_zones.py report
      -> ringkasan rasio tata letak (median semua sampel) yang dipakai sebagai acuan posisi zona

Butuh: pillow, numpy, opencv-python (opencv dipakai memilih coretan terbesar dan membuat *_clean_approx.jpg).
Foto latih sungguhan HARUS diambil tanpa coretan (Atur > Latih Kamera). Berkas ini hanya untuk posisi zona.
"""
import argparse, json, os, statistics, sys
import numpy as np
from PIL import Image

DB = os.path.join(os.path.dirname(__file__), "..", "docs", "samples", "drum_zones.json")
STROKE = {"counted": (227, 110, 102), "tenth": (247, 203, 70), "marker": (66, 186, 95)}   # RGB rata-rata coretan


def near(a, c, t):
    return np.abs(a - np.array(c)).sum(2) < t


def zone_masks(a):
    red = near(a, STROKE["counted"], 110)
    ye = near(a, STROKE["tenth"], 110)
    gr = near(a, STROKE["marker"], 70)
    # angka merah drum 1/10 berwarna mirip coretan merah, dan foto memuat warna lain (kompas, pantulan):
    # kuning dicari dulu, merah hanya di kiri kuning, hijau hanya tepat di bawah/di sekitar kuning
    g = biggest_stroke(ye) if ye.any() else None
    if g is not None:
        ys, xs = np.nonzero(g)
        x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
        red[:, max(0, x0 - 4):] = False
        gr[:, :max(0, x0 - 20)] = False; gr[:, x1 + 20:] = False; gr[:y0, :] = False; gr[y1 + 200:, :] = False
        ye = g
    return {"counted": red, "tenth": ye, "marker": gr}


def biggest_stroke(m):
    """Gugus piksel tersambung terbesar (coretan = satu garis utuh); noise warna lain di foto dibuang."""
    import cv2
    m8 = cv2.dilate(m.astype(np.uint8) * 255, np.ones((7, 7), np.uint8))
    n, lab, st, _ = cv2.connectedComponentsWithStats(m8, connectivity=8)
    if n < 2:
        return None
    k = 1 + int(np.argmax(st[1:, cv2.CC_STAT_WIDTH] * st[1:, cv2.CC_STAT_HEIGHT]))
    return (lab == k) & m


def boxes(img):
    a = np.array(img.convert("RGB")).astype(int)
    out = {}
    for k, m in zone_masks(a).items():
        g = biggest_stroke(m)
        ys, xs = np.nonzero(g) if g is not None else ([], [])
        if len(xs) < 80:
            raise SystemExit(f"coretan {k} tidak ditemukan (piksel {len(xs)})")
        out[k] = [int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max())]
    return out


def layout(b):
    """Rasio tata letak relatif terhadap zona 'counted' (lebar cw, tinggi ch)."""
    cl, ct, cr, cb = b["counted"]; cw, ch = cr - cl, cb - ct
    tl, tt, tr, tb = b["tenth"]; ml, mt, mr, mb = b["marker"]
    return {
        "tenth_gap_x": (tl - cr) / cw, "tenth_w": (tr - tl) / cw, "tenth_top": (tt - ct) / ch, "tenth_h": (tb - tt) / ch,
        "marker_dx": (ml - tl) / cw, "marker_w": (mr - ml) / cw, "marker_top": (mt - ct) / ch, "marker_h": (mb - mt) / ch,
    }


def load():
    return json.load(open(DB)) if os.path.exists(DB) else {"zones": ["counted", "marker", "tenth"], "samples": []}


def add(a):
    img = Image.open(a.photo); W, H = img.size
    b = boxes(img)
    row = {
        "file": os.path.basename(a.photo), "size": [W, H], "counted": a.counted, "tenth": a.tenth, "unit": a.unit,
        "boxes": {k: [round(v[0] / W, 4), round(v[1] / H, 4), round(v[2] / W, 4), round(v[3] / H, 4)] for k, v in b.items()},
        "layout": {k: round(v, 3) for k, v in layout(b).items()},
        "note": "kotak = bagian dalam coretan, sudah termasuk garis; pakai untuk posisi relatif, bukan batas piksel",
    }
    d = load(); d["samples"] = [s for s in d["samples"] if s["file"] != row["file"]] + [row]
    os.makedirs(os.path.dirname(DB), exist_ok=True); json.dump(d, open(DB, "w"), indent=2, ensure_ascii=False)
    print("tersimpan:", row["file"], row["boxes"])
    if True:
        import cv2
        bgr = cv2.imread(a.photo); arr = bgr[..., ::-1].astype(int)
        m = np.zeros(arr.shape[:2], np.uint8)
        for v in zone_masks(arr).values():
            g = biggest_stroke(v)
            if g is not None:
                m |= g.astype(np.uint8) * 255
        m = cv2.dilate(m, np.ones((5, 5), np.uint8))
        out = os.path.splitext(a.photo)[0] + "_clean_approx.jpg"
        cv2.imwrite(out, cv2.inpaint(bgr, m, 5, cv2.INPAINT_TELEA)); print("tanpa coretan (pendekatan):", out)


def report(_):
    d = load()
    if not d["samples"]:
        sys.exit("belum ada sampel")
    keys = d["samples"][0]["layout"].keys()
    print(f"{len(d['samples'])} sampel")
    for k in keys:
        v = [s["layout"][k] for s in d["samples"]]
        print(f"  {k:12s} median {statistics.median(v):6.3f}   sebaran {min(v):6.3f}..{max(v):6.3f}")


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sp = p.add_subparsers(dest="cmd", required=True)
    q = sp.add_parser("add"); q.add_argument("photo"); q.add_argument("--counted", required=True)
    q.add_argument("--tenth", type=int, required=True); q.add_argument("--unit", default=""); q.set_defaults(f=add)
    sp.add_parser("report").set_defaults(f=report)
    a = p.parse_args(); a.f(a)
