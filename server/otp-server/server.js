// Server OTP minimal untuk HM Tracker (tanpa dependensi, Node 18+).
//   node server.js
// Env:
//   PORT                  default 8080
//   DELIVERY_WEBHOOK_URL  endpoint yang MENGIRIM pesan (email/WhatsApp), menerima POST JSON
//                         {"channel":"email"|"wa","ident":"...","message":"Kode HM Tracker: 123456"}
//                         Sambungkan ke Resend/SendGrid/Fonnte/Twilio dsb di sini; kunci API tetap di server.
//   DELIVERY_WEBHOOK_AUTH nilai header Authorization untuk webhook (opsional)
//   DEV_LOG_CODE=1        cetak kode ke log (HANYA untuk uji; jangan di produksi)
// Pasang di belakang HTTPS (reverse proxy). Penyimpanan in-memory: ganti Redis bila lebih dari satu instance.
const http = require('http');
const crypto = require('crypto');

const PORT = process.env.PORT || 8080;
const TTL_MS = 5 * 60 * 1000, MAX_TRIES = 5, RESEND_MS = 60 * 1000, MAX_PER_HOUR = 5;
const reqs = new Map();   // requestId -> {ident, hash, exp, tries, used}
const sends = new Map();  // ident -> [timestamps]

const sha = (s) => crypto.createHash('sha256').update(s).digest('hex');
const reply = (res, code, obj) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(obj)); };
const err = (res, code, error) => reply(res, code, { ok: false, error });

async function deliver(channel, ident, code) {
  const message = `Kode HM Tracker: ${code}. Berlaku 5 menit. Jangan bagikan kode ini.`;
  if (process.env.DEV_LOG_CODE === '1') console.log(`[DEV] ${channel} ${ident}: ${code}`);
  const url = process.env.DELIVERY_WEBHOOK_URL;
  if (!url) { if (process.env.DEV_LOG_CODE !== '1') throw new Error('DELIVERY_WEBHOOK_URL belum diatur'); return; }
  const r = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(process.env.DELIVERY_WEBHOOK_AUTH ? { Authorization: process.env.DELIVERY_WEBHOOK_AUTH } : {}) },
    body: JSON.stringify({ channel, ident, message }),
  });
  if (!r.ok) throw new Error('pengiriman gagal ' + r.status);
}

function body(req) {
  return new Promise((ok, no) => {
    let b = ''; req.on('data', (c) => { b += c; if (b.length > 4096) { no(new Error('too big')); req.destroy(); } });
    req.on('end', () => { try { ok(JSON.parse(b || '{}')); } catch (e) { no(e); } });
  });
}

http.createServer(async (req, res) => {
  if (req.method !== 'POST') return err(res, 404, 'tidak ditemukan');
  let j; try { j = await body(req); } catch { return err(res, 400, 'permintaan tidak valid'); }
  const now = Date.now();

  if (req.url === '/otp/send') {
    const channel = j.channel, ident = String(j.ident || '').trim().toLowerCase();
    if (channel !== 'email' && channel !== 'wa') return err(res, 400, 'channel tidak valid');
    if (channel === 'email' ? !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(ident) : !/^\d{10,15}$/.test(ident)) return err(res, 400, 'identitas tidak valid');
    const hist = (sends.get(ident) || []).filter((t) => now - t < 3600e3);
    if (hist.length && now - hist[hist.length - 1] < RESEND_MS) return err(res, 429, 'Tunggu 1 menit sebelum minta OTP lagi');
    if (hist.length >= MAX_PER_HOUR) return err(res, 429, 'Terlalu banyak permintaan OTP, coba lagi nanti');
    const code = String(crypto.randomInt(0, 1000000)).padStart(6, '0');
    const requestId = crypto.randomUUID();
    try { await deliver(channel, ident, code); } catch (e) { console.error(e.message); return err(res, 502, 'Gagal mengirim OTP'); }
    hist.push(now); sends.set(ident, hist);
    reqs.set(requestId, { ident, hash: sha(requestId + code), exp: now + TTL_MS, tries: 0 });
    return reply(res, 200, { ok: true, requestId });
  }

  if (req.url === '/otp/verify') {
    const r = reqs.get(String(j.requestId || ''));
    if (!r || r.exp < now) { reqs.delete(j.requestId); return err(res, 400, 'OTP kedaluwarsa, kirim ulang'); }
    if (++r.tries > MAX_TRIES) { reqs.delete(j.requestId); return err(res, 429, 'Terlalu banyak percobaan, kirim ulang OTP'); }
    const a = Buffer.from(r.hash), b = Buffer.from(sha(j.requestId + String(j.code || '')));
    if (!crypto.timingSafeEqual(a, b)) return err(res, 400, 'OTP salah');
    reqs.delete(j.requestId);   // sekali pakai
    return reply(res, 200, { ok: true });
  }
  err(res, 404, 'tidak ditemukan');
}).listen(PORT, () => console.log('OTP server di :' + PORT));

setInterval(() => { const n = Date.now(); for (const [k, v] of reqs) if (v.exp < n) reqs.delete(k); }, 60e3).unref();
