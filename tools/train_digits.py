#!/usr/bin/env python3
"""
Latih pembaca angka meter (CNN + CTC) dari dataset yang diekspor aplikasi, lalu hasilkan hm_digits_model.zip
untuk dipasang di aplikasi (Atur > Latih Kamera > Pasang model).

  pip install tensorflow pillow numpy
  python tools/train_digits.py hm_dataset.zip --out hm_digits_model.zip

Model hanya memakai operasi bawaan TFLite (tanpa LSTM), jadi tidak butuh Flex delegate.
Pra-olah gambar (prep) HARUS sama dengan prepForModel di aplikasi (DigitModel.kt).
"""
import argparse, csv, io, json, random, sys, zipfile
import numpy as np
from PIL import Image, ImageEnhance, ImageFilter

CHARSET = "0123456789."
H, W = 32, 160
BLANK = len(CHARSET)


def prep(img: Image.Image) -> np.ndarray:
    """Cermin dari prepForOcr(crop, H, null) + prepForModel: abu-abu, regang persentil 2-98, balik bila gelap, tinggi H, lebar maks W."""
    ow = int(np.clip(int(H * img.width / img.height), 16, 2400))
    im = img.convert("RGB").resize((ow, H), Image.BILINEAR)
    a = np.asarray(im, dtype=np.int64)
    g = (a[..., 0] * 299 + a[..., 1] * 587 + a[..., 2] * 114) // 1000
    total = g.size
    hist = np.bincount(g.ravel(), minlength=256)
    cs = np.cumsum(hist)
    lo = int(np.argmax(cs >= total * 0.02))
    hi = 255 - int(np.argmax(np.cumsum(hist[::-1]) >= total * 0.02))
    inv = g.mean() < 110
    q = np.clip(((g - lo) * 255) // max(1, hi - lo), 0, 255)
    if inv:
        q = 255 - q
    out = np.ones((H, W), dtype=np.float32)
    if ow > W:
        q = np.asarray(Image.fromarray(q.astype(np.uint8)).resize((W, H), Image.BILINEAR))
        ow = W
    out[:, :ow] = q[:, :ow] / 255.0
    return out


def augment(img: Image.Image) -> Image.Image:
    if random.random() < 0.3:                              # polaritas acak (meter terang/gelap)
        img = Image.eval(img.convert("RGB"), lambda v: 255 - v)
    w, h = img.size
    mx, my = int(w * random.uniform(0, .06)), int(h * random.uniform(0, .12))
    img = img.crop((random.randint(0, mx), random.randint(0, my), w - random.randint(0, mx), h - random.randint(0, my)))
    img = img.rotate(random.uniform(-3, 3), resample=Image.BILINEAR, expand=True, fillcolor=(128, 128, 128))
    img = ImageEnhance.Brightness(img).enhance(random.uniform(.6, 1.4))
    img = ImageEnhance.Contrast(img).enhance(random.uniform(.6, 1.5))
    if random.random() < .5:
        img = img.filter(ImageFilter.GaussianBlur(random.uniform(0, 1.6)))
    if random.random() < .5:
        a = np.asarray(img.convert("RGB"), dtype=np.float32)
        a = np.clip(a + np.random.normal(0, random.uniform(2, 14), a.shape), 0, 255).astype(np.uint8)
        img = Image.fromarray(a)
    return img


def load(path):
    items = []
    with zipfile.ZipFile(path) as z:
        rows = list(csv.DictReader(io.TextIOWrapper(z.open("labels.csv"), "utf-8")))
        for r in rows:
            lab = r["label"].strip()
            if not lab or any(c not in CHARSET for c in lab):
                continue
            items.append((Image.open(io.BytesIO(z.read(r["file"]))).convert("RGB"), lab))
    return items


def encode(labels):
    ml = max(len(l) for l in labels)
    arr = np.full((len(labels), ml), BLANK, dtype=np.int32)
    for i, l in enumerate(labels):
        for j, c in enumerate(l):
            arr[i, j] = CHARSET.index(c)
    return arr, np.array([len(l) for l in labels], dtype=np.int32)


def build(tf):
    from tensorflow.keras import layers, Model
    inp = layers.Input((H, W, 1))
    x = inp
    for f, pool in [(32, (2, 2)), (64, (2, 2)), (128, (2, 1)), (128, (2, 1))]:
        x = layers.Conv2D(f, 3, padding="same", use_bias=False)(x)
        x = layers.BatchNormalization()(x)
        x = layers.ReLU()(x)
        x = layers.MaxPool2D(pool)(x)                      # (2, 40, 128) di akhir
    x = layers.Permute((2, 1, 3))(x)
    x = layers.Reshape((W // 4, (H // 16) * 128))(x)       # (T=40, 256)
    x = layers.Conv1D(256, 3, padding="same", activation="relu")(x)
    x = layers.Dropout(0.25)(x)
    logits = layers.Dense(len(CHARSET) + 1)(x)
    return Model(inp, logits), inp, logits


def decode(p):
    out, prev = [], -1
    for k in p.argmax(-1):
        if k != prev and k != BLANK:
            out.append(CHARSET[k])
        prev = k
    return "".join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dataset"); ap.add_argument("--out", default="hm_digits_model.zip")
    ap.add_argument("--epochs", type=int, default=80); ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--aug", type=int, default=8, help="salinan augmentasi per sampel tiap epoch")
    args = ap.parse_args()
    import tensorflow as tf
    random.seed(1); np.random.seed(1); tf.random.set_seed(1)

    items = load(args.dataset)
    if len(items) < 60:
        sys.exit(f"Sampel terlalu sedikit ({len(items)}). Kumpulkan minimal 300 (idealnya 1000+).")
    if len(items) < 300:
        print(f"PERINGATAN: hanya {len(items)} sampel; model kemungkinan belum akurat.")
    random.shuffle(items)
    nv = max(10, len(items) // 10)
    val, trn = items[:nv], items[nv:]
    xv = np.stack([prep(i) for i, _ in val])[..., None]

    model, inp, logits = build(tf)
    opt = tf.keras.optimizers.Adam(1e-3)
    T = W // 4

    @tf.function
    def step(x, y, yl):
        with tf.GradientTape() as tape:
            lg = model(x, training=True)
            loss = tf.reduce_mean(tf.nn.ctc_loss(
                labels=y, logits=lg, label_length=yl, logit_length=tf.fill([tf.shape(x)[0]], T),
                logits_time_major=False, blank_index=BLANK))
        opt.apply_gradients(zip(tape.gradient(loss, model.trainable_variables), model.trainable_variables))
        return loss

    def val_acc():
        pr = tf.nn.softmax(model(xv, training=False)).numpy()
        return float(np.mean([decode(p) == l for p, (_, l) in zip(pr, val)]))

    best, best_w, bad = -1.0, None, 0
    for ep in range(1, args.epochs + 1):
        data = [(augment(i), l) for i, l in trn for _ in range(args.aug)]
        random.shuffle(data)
        tl, n = 0.0, 0
        for b in range(0, len(data), args.batch):
            chunk = data[b:b + args.batch]
            x = np.stack([prep(i) for i, _ in chunk])[..., None]
            y, yl = encode([l for _, l in chunk])
            tl += float(step(tf.constant(x), tf.constant(y), tf.constant(yl))); n += 1
        acc = val_acc()
        print(f"epoch {ep:3d}  loss {tl / n:7.3f}  val_exact {acc * 100:5.1f}%")
        if acc > best:
            best, best_w, bad = acc, model.get_weights(), 0
        else:
            bad += 1
            if bad >= 12:
                break
    model.set_weights(best_w)
    print(f"Akurasi validasi terbaik: {best * 100:.1f}% ({len(val)} sampel validasi)")

    export = tf.keras.Model(inp, tf.keras.layers.Softmax()(logits))
    conv = tf.lite.TFLiteConverter.from_keras_model(export)
    conv.optimizations = [tf.lite.Optimize.DEFAULT]
    conv.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
    tfl = conv.convert()

    itp = tf.lite.Interpreter(model_content=tfl); itp.allocate_tensors()
    ii, oo = itp.get_input_details()[0], itp.get_output_details()[0]
    ok = 0
    for x, (_, l) in zip(xv, val):
        itp.set_tensor(ii["index"], x[None].astype(np.float32)); itp.invoke()
        ok += decode(itp.get_tensor(oo["index"])[0]) == l
    print(f"Akurasi validasi model TFLite: {ok / len(val) * 100:.1f}%")

    with zipfile.ZipFile(args.out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("digits.tflite", tfl)
        z.writestr("digits.json", json.dumps({"h": H, "w": W, "charset": CHARSET}))
    print(f"Tersimpan: {args.out}. Pasang di aplikasi: Atur > Latih Kamera > Pasang model (zip).")


if __name__ == "__main__":
    main()
