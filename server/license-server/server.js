// Server lisensi + Adm Lock untuk HM Tracker (Node 18+, tanpa dependensi).
//   node server.js
// Env:
//   PORT                   default 8081
//   DATA_DIR               default ./data (db.json + license-private.pem; JANGAN di-commit)
//   ADMIN_EMAILS           daftar email admin dipisah koma (wajib; hanya email ini bisa masuk)
//   DELIVERY_WEBHOOK_URL   endpoint pengirim OTP admin (sama kontraknya dengan otp-server)
//   DELIVERY_WEBHOOK_AUTH  nilai header Authorization untuk webhook (opsional)
//   DEV_LOG_CODE=1         cetak OTP admin ke log (HANYA uji)
//   LICENSE_PRIVATE_KEY_PEM  kunci privat PKCS8 PEM (opsional; bila kosong dibuat di DATA_DIR)
//   LICENSE_TTL_DAYS       batas offline lisensi, default 7
//   MAX_DEVICES            maks. perangkat per akun, default 2
//   TRUST_PROXY=1          pakai X-Forwarded-For untuk batas laju
// Pasang di belakang HTTPS. Satu proses saja (penyimpanan berkas JSON).
'use strict';
const http = require('http');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const PORT = Number(process.env.PORT) || 8081;
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data');
const ADMINS = (process.env.ADMIN_EMAILS || '').split(',').map((s) => s.trim().toLowerCase()).filter(Boolean);
const LICENSE_TTL_MS = (Number(process.env.LICENSE_TTL_DAYS) || 7) * 864e5;
const MAX_DEVICES = Number(process.env.MAX_DEVICES) || 2;
const DAY = 864e5;
const OTP_TTL_MS = 5 * 60e3, OTP_TRIES = 5, RESEND_MS = 60e3, OTP_PER_HOUR = 5;
const TOKEN_TTL_MS = 30 * 60e3, TOKEN_MAX_MS = 8 * 3600e3;

// ---- paket & fitur (harus sama dengan enum Feature di Tier.kt) ----
const ALL = ['CAM_LEVEL', 'CAM_FOCUS_LOCK', 'WM_EXTRA_TEMPLATES', 'EDIT_ROTATE', 'EDIT_CONTRAST',
  'CALC_FORECAST', 'CALC_EXPORT', 'CALC_TREND', 'CALC_COMPARE', 'CALC_REPORT'];
const PREMIUM = ['CAM_LEVEL', 'WM_EXTRA_TEMPLATES', 'EDIT_ROTATE', 'CALC_FORECAST', 'CALC_EXPORT'];
const TIERS = { basic: [], premium: PREMIUM, platinum: ALL, vip: ALL };   // vip sementara = platinum; ubah di sini
const STATUSES = ['active', 'suspended', 'blocked'];

// ---- kunci tanda tangan ECDSA P-256 ----
fs.mkdirSync(DATA_DIR, { recursive: true });
const keyFile = path.join(DATA_DIR, 'license-private.pem');
let privateKey;
if (process.env.LICENSE_PRIVATE_KEY_PEM) privateKey = crypto.createPrivateKey(process.env.LICENSE_PRIVATE_KEY_PEM.replace(/\\n/g, '\n'));
else if (fs.existsSync(keyFile)) privateKey = crypto.createPrivateKey(fs.readFileSync(keyFile));
else {
  privateKey = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' }).privateKey;
  fs.writeFileSync(keyFile, privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600 });
}
const publicB64 = crypto.createPublicKey(privateKey).export({ type: 'spki', format: 'der' }).toString('base64');

// ---- basis data (berkas JSON, ditulis atomik) ----
const dbFile = path.join(DATA_DIR, 'db.json');
let db = { users: {}, devices: [], vouchers: {}, log: [], config: { minVersionCode: 0 } };
if (fs.existsSync(dbFile)) db = Object.assign(db, JSON.parse(fs.readFileSync(dbFile, 'utf8')));
const save = () => { const t = dbFile + '.tmp'; fs.writeFileSync(t, JSON.stringify(db)); fs.renameSync(t, dbFile); };

const sha = (s) => crypto.createHash('sha256').update(s).digest('hex');
const reply = (res, code, obj) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(obj)); };
const err = (res, code, error, errCode) => reply(res, code, { ok: false, error, ...(errCode ? { code: errCode } : {}) });
const normIdent = (s) => String(s || '').trim().toLowerCase();
const validIdent = (s) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(s) || /^\d{10,15}$/.test(s);
const dec = (s) => { try { return decodeURIComponent(s); } catch { return ''; } };

// Log admin: hanya bisa ditambah, tiap entri dirantai dengan hash entri sebelumnya (tidak ada endpoint hapus).
function audit(admin, action, target, detail) {
  const prev = db.log.length ? db.log[db.log.length - 1].h : '';
  const e = { ts: Date.now(), admin, action, target: target || '', detail: detail || {}, prev };
  e.h = sha(prev + JSON.stringify([e.ts, e.admin, e.action, e.target, e.detail]));
  db.log.push(e);
}

function newUser(ident, now) {
  return { ident, tier: 'basic', status: 'active', validUntil: null, featuresAdd: [], featuresRemove: [], msg: '', createdAt: now, lastSeen: 0, appVersion: 0 };
}
function getUser(ident, now) { return db.users[ident] || (db.users[ident] = newUser(ident, now)); }

function effective(u, now) {
  let tier = u.tier, features = [];
  if (u.validUntil && u.validUntil < now) tier = 'basic';
  if (u.status === 'active') {
    const set = new Set(TIERS[tier] || []);
    u.featuresAdd.forEach((f) => set.add(f));
    u.featuresRemove.forEach((f) => set.delete(f));
    features = ALL.filter((f) => set.has(f));
  } else tier = 'basic';
  return { tier, features };
}

function signLicense(u, deviceId, now) {
  const { tier, features } = effective(u, now);
  const payload = JSON.stringify({
    ident: u.ident, tier, status: u.status, features, deviceId, iat: now, exp: now + LICENSE_TTL_MS,
    validUntil: u.validUntil, msg: u.msg, minVersionCode: db.config.minVersionCode,
  });
  const sig = crypto.sign('sha256', Buffer.from(payload), privateKey).toString('base64');   // DER = format SHA256withECDSA Android
  return { payload, sig };
}

const devicesOf = (ident) => db.devices.filter((d) => d.ident === ident);
const userView = (u) => ({ ...u, deviceCount: devicesOf(u.ident).filter((d) => !d.revoked).length });
const detailView = (u) => ({ ok: true, user: userView(u), devices: devicesOf(u.ident) });

// ---- OTP admin ----
const otpReqs = new Map(), otpSends = new Map(), tokens = new Map(), hits = new Map();

async function deliver(email, code) {
  const message = `Kode Adm Lock HM Tracker: ${code}. Berlaku 5 menit. Jangan bagikan kode ini.`;
  if (process.env.DEV_LOG_CODE === '1') console.log(`[DEV] ${email}: ${code}`);
  const url = process.env.DELIVERY_WEBHOOK_URL;
  if (!url) { if (process.env.DEV_LOG_CODE !== '1') throw new Error('DELIVERY_WEBHOOK_URL belum diatur'); return; }
  const r = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(process.env.DELIVERY_WEBHOOK_AUTH ? { Authorization: process.env.DELIVERY_WEBHOOK_AUTH } : {}) },
    body: JSON.stringify({ channel: 'email', ident: email, message }),
  });
  if (!r.ok) throw new Error('pengiriman gagal ' + r.status);
}

function limited(req) {
  const ip = (process.env.TRUST_PROXY === '1' && String(req.headers['x-forwarded-for'] || '').split(',')[0].trim()) || req.socket.remoteAddress || '?';
  const now = Date.now(); const h = hits.get(ip);
  if (!h || now - h.t > 60e3) { hits.set(ip, { t: now, n: 1 }); return false; }
  return ++h.n > 120;
}

function readBody(req) {
  return new Promise((ok, no) => {
    let b = '';
    req.on('data', (c) => { b += c; if (b.length > 8192) { no(new Error('too big')); req.destroy(); } });
    req.on('end', () => { try { ok(JSON.parse(b || '{}')); } catch (e) { no(e); } });
  });
}

function adminOf(req) {
  const m = /^Bearer ([0-9a-f]{64})$/.exec(req.headers.authorization || '');
  const t = m && tokens.get(m[1]);
  if (!t || t.exp < Date.now()) return null;
  t.exp = Math.min(Date.now() + TOKEN_TTL_MS, t.maxExp);
  return t.email;
}

// ---- patch pengguna: validasi dulu, baru terapkan ----
function patchUser(u, j) {
  const ch = {};
  if ('tier' in j && !TIERS[j.tier]) return 'paket tidak dikenal';
  if ('status' in j && !STATUSES.includes(j.status)) return 'status tidak valid';
  if ('validUntil' in j && j.validUntil !== null && !(Number.isFinite(j.validUntil) && j.validUntil > 0)) return 'tanggal berlaku tidak valid';
  for (const k of ['featuresAdd', 'featuresRemove']) {
    if (k in j && !(Array.isArray(j[k]) && j[k].every((f) => ALL.includes(f)))) return 'daftar fitur tidak valid';
  }
  if ('msg' in j && (typeof j.msg !== 'string' || j.msg.length > 200)) return 'pesan maksimal 200 karakter';
  for (const k of ['tier', 'status', 'validUntil', 'msg']) if (k in j && JSON.stringify(u[k]) !== JSON.stringify(j[k])) { ch[k] = [u[k], j[k]]; u[k] = j[k]; }
  for (const k of ['featuresAdd', 'featuresRemove']) if (k in j) {
    const nv = ALL.filter((f) => j[k].includes(f));
    if (JSON.stringify(u[k]) !== JSON.stringify(nv)) { ch[k] = [u[k], nv]; u[k] = nv; }
  }
  return ch;
}

function newCode() {
  const A = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; let s = '';
  for (let i = 0; i < 16; i++) s += A[crypto.randomInt(A.length)];
  return s.match(/.{4}/g).join('-');
}

async function handle(req, res) {
  const url = new URL(req.url, 'http://x'); const p = url.pathname;
  let method = req.method; const now = Date.now();
  if (limited(req)) return err(res, 429, 'Terlalu banyak permintaan');
  if (method === 'GET' && p === '/license/pubkey') return reply(res, 200, { ok: true, alg: 'SHA256withECDSA', publicKey: publicB64 });
  let j = {};
  if (method !== 'GET' && method !== 'DELETE') { try { j = await readBody(req); } catch { return err(res, 400, 'permintaan tidak valid'); } }
  if (method === 'POST' && req.headers['x-http-method-override'] === 'PATCH') method = 'PATCH';

  // ===== APK user =====
  if (method === 'POST' && p === '/license/check') {
    const ident = normIdent(j.ident), deviceId = String(j.deviceId || '');
    if (!validIdent(ident) || !/^[\w-]{8,64}$/.test(deviceId)) return err(res, 400, 'data tidak valid');
    let d = db.devices.find((x) => x.ident === ident && x.deviceId === deviceId);
    if (d && d.revoked) return err(res, 403, 'Perangkat ini dicabut admin', 'device_revoked');
    if (!d) {
      if (devicesOf(ident).filter((x) => !x.revoked).length >= MAX_DEVICES) return err(res, 403, 'Batas perangkat tercapai', 'device_limit');
      d = { ident, deviceId, firstSeen: now, lastSeen: now, appVersion: 0, revoked: false }; db.devices.push(d);
    }
    const u = getUser(ident, now);
    d.lastSeen = u.lastSeen = now; d.appVersion = u.appVersion = Number(j.appVersion) || 0;
    save();
    return reply(res, 200, { ok: true, license: signLicense(u, deviceId, now) });
  }

  if (method === 'POST' && p === '/license/redeem') {
    const ident = normIdent(j.ident), deviceId = String(j.deviceId || '');
    const code = String(j.code || '').toUpperCase().replace(/[^A-Z0-9]/g, '').match(/.{1,4}/g)?.join('-') || '';
    const d = db.devices.find((x) => x.ident === ident && x.deviceId === deviceId && !x.revoked);
    if (!d) return err(res, 403, 'Perangkat belum terdaftar', 'device_unknown');
    const v = db.vouchers[code];
    if (!v || v.revoked) return err(res, 400, 'Kode tidak valid');
    if ((v.expiresAt && v.expiresAt < now) || v.used >= v.maxUse || v.usedBy.includes(ident)) return err(res, 400, 'Kode sudah habis atau kedaluwarsa');
    const u = getUser(ident, now);
    const base = u.tier === v.tier && u.validUntil && u.validUntil > now ? u.validUntil : now;
    u.tier = v.tier; u.validUntil = base + v.days * DAY;
    v.used++; v.usedBy.push(ident);
    audit('system', 'voucher.redeem', ident, { code, tier: v.tier, days: v.days });
    save();
    return reply(res, 200, { ok: true, license: signLicense(u, deviceId, now) });
  }

  if (p.startsWith('/admin/')) return admin(req, res, method, p, url, j, now);
  err(res, 404, 'tidak ditemukan');
}

function admin(req, res, method, p, url, j, now) {
  // --- login admin (OTP + daftar putih) ---
  if (method === 'POST' && p === '/admin/login/send') {
    const email = normIdent(j.email);
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return err(res, 400, 'email tidak valid');
    const requestId = crypto.randomUUID();
    if (!ADMINS.includes(email)) return reply(res, 200, { ok: true, requestId });   // balasan sama: tidak membocorkan siapa admin
    const hist = (otpSends.get(email) || []).filter((t) => now - t < 3600e3);
    if (hist.length && now - hist[hist.length - 1] < RESEND_MS) return err(res, 429, 'Tunggu 1 menit sebelum minta OTP lagi');
    if (hist.length >= OTP_PER_HOUR) return err(res, 429, 'Terlalu banyak permintaan OTP, coba lagi nanti');
    const code = String(crypto.randomInt(0, 1000000)).padStart(6, '0');
    return deliver(email, code).then(() => {
      hist.push(now); otpSends.set(email, hist);
      otpReqs.set(requestId, { email, hash: sha(requestId + code), exp: now + OTP_TTL_MS, tries: 0 });
      reply(res, 200, { ok: true, requestId });
    }).catch((e) => { console.error(e.message); err(res, 502, 'Gagal mengirim OTP'); });
  }
  if (method === 'POST' && p === '/admin/login/verify') {
    const id = String(j.requestId || ''), r = otpReqs.get(id);
    if (!r || r.exp < now) { otpReqs.delete(id); return err(res, 400, 'OTP salah atau kedaluwarsa'); }
    if (++r.tries > OTP_TRIES) { otpReqs.delete(id); return err(res, 429, 'Terlalu banyak percobaan, kirim ulang OTP'); }
    if (!crypto.timingSafeEqual(Buffer.from(r.hash), Buffer.from(sha(id + String(j.code || ''))))) return err(res, 400, 'OTP salah atau kedaluwarsa');
    otpReqs.delete(id);
    const token = crypto.randomBytes(32).toString('hex');
    tokens.set(token, { email: r.email, exp: now + TOKEN_TTL_MS, maxExp: now + TOKEN_MAX_MS });
    audit(r.email, 'admin.login', '', {}); save();
    return reply(res, 200, { ok: true, token, email: r.email });
  }

  const who = adminOf(req);
  if (!who) return err(res, 401, 'Sesi admin berakhir, masuk lagi');
  let m;

  if (method === 'GET' && p === '/admin/me') return reply(res, 200, { ok: true, email: who });
  if (method === 'POST' && p === '/admin/logout') { tokens.delete((req.headers.authorization || '').slice(7)); return reply(res, 200, { ok: true }); }
  if (method === 'GET' && p === '/admin/tiers') return reply(res, 200, { ok: true, tiers: TIERS, features: ALL, maxDevices: MAX_DEVICES });
  if (method === 'GET' && p === '/admin/config') return reply(res, 200, { ok: true, ...db.config });
  if (method === 'PATCH' && p === '/admin/config') {
    const n = j.minVersionCode;
    if (!Number.isInteger(n) || n < 0) return err(res, 400, 'versi minimum tidak valid');
    audit(who, 'config.minVersion', '', { from: db.config.minVersionCode, to: n });
    db.config.minVersionCode = n; save();
    return reply(res, 200, { ok: true, ...db.config });
  }

  if (method === 'GET' && p === '/admin/users') {
    const q = normIdent(url.searchParams.get('q'));
    const list = Object.values(db.users).filter((u) => !q || u.ident.includes(q))
      .sort((a, b) => (b.lastSeen || b.createdAt) - (a.lastSeen || a.createdAt)).slice(0, 100).map(userView);
    return reply(res, 200, { ok: true, users: list });
  }

  if ((m = /^\/admin\/users\/([^/]+)$/.exec(p))) {
    const ident = normIdent(dec(m[1]));
    if (!validIdent(ident)) return err(res, 400, 'identitas tidak valid');
    if (method === 'GET') { const u = db.users[ident]; return u ? reply(res, 200, detailView(u)) : err(res, 404, 'akun tidak ditemukan'); }
    if (method === 'PATCH') {   // juga membuat akun baru (admin boleh memberi paket sebelum user login)
      const u = getUser(ident, now); const ch = patchUser(u, j);
      if (typeof ch === 'string') return err(res, 400, ch);
      audit(who, 'user.update', ident, ch); save();
      return reply(res, 200, detailView(u));
    }
  }
  if ((m = /^\/admin\/users\/([^/]+)\/(block|unblock)$/.exec(p)) && method === 'POST') {
    const ident = normIdent(dec(m[1])), u = db.users[ident];
    if (!u) return err(res, 404, 'akun tidak ditemukan');
    const to = m[2] === 'block' ? 'blocked' : 'active';
    audit(who, 'user.' + m[2], ident, { from: u.status, to }); u.status = to; save();
    return reply(res, 200, detailView(u));
  }
  if ((m = /^\/admin\/users\/([^/]+)\/devices$/.exec(p)) && method === 'GET') {
    const ident = normIdent(dec(m[1]));
    return reply(res, 200, { ok: true, devices: devicesOf(ident) });
  }
  if ((m = /^\/admin\/users\/([^/]+)\/devices\/([^/]+)$/.exec(p)) && method === 'DELETE') {
    const ident = normIdent(dec(m[1])), id = dec(m[2]);
    const d = db.devices.find((x) => x.ident === ident && x.deviceId === id);
    if (!d) return err(res, 404, 'perangkat tidak ditemukan');
    d.revoked = true; audit(who, 'device.revoke', ident, { deviceId: id }); save();
    return reply(res, 200, { ok: true, devices: devicesOf(ident) });
  }

  if (method === 'GET' && p === '/admin/vouchers') return reply(res, 200, { ok: true, vouchers: Object.values(db.vouchers).sort((a, b) => b.createdAt - a.createdAt).slice(0, 200) });
  if (method === 'POST' && p === '/admin/vouchers') {
    const days = j.days, maxUse = j.maxUse, exp = j.expiresInDays;
    if (!TIERS[j.tier] || j.tier === 'basic') return err(res, 400, 'pilih paket berbayar');
    if (!Number.isInteger(days) || days < 1 || days > 3650) return err(res, 400, 'durasi 1-3650 hari');
    if (!Number.isInteger(maxUse) || maxUse < 1 || maxUse > 1000) return err(res, 400, 'batas pakai 1-1000');
    if (exp != null && (!Number.isInteger(exp) || exp < 1 || exp > 3650)) return err(res, 400, 'masa kode 1-3650 hari');
    const code = newCode();
    const v = db.vouchers[code] = { code, tier: j.tier, days, maxUse, used: 0, usedBy: [], expiresAt: exp ? now + exp * DAY : null, revoked: false, createdAt: now, createdBy: who };
    audit(who, 'voucher.create', code, { tier: v.tier, days, maxUse }); save();
    return reply(res, 200, { ok: true, voucher: v });
  }
  if ((m = /^\/admin\/vouchers\/([^/]+)\/revoke$/.exec(p)) && method === 'POST') {
    const v = db.vouchers[dec(m[1])];
    if (!v) return err(res, 404, 'kode tidak ditemukan');
    v.revoked = true; audit(who, 'voucher.revoke', v.code, {}); save();
    return reply(res, 200, { ok: true, voucher: v });
  }

  if (method === 'GET' && p === '/admin/log') {
    const lim = Math.min(Number(url.searchParams.get('limit')) || 100, 500);
    return reply(res, 200, { ok: true, log: db.log.slice(-lim).reverse() });
  }
  err(res, 404, 'tidak ditemukan');
}

http.createServer((req, res) => {
  Promise.resolve(handle(req, res)).catch((e) => { console.error(e); if (!res.headersSent) err(res, 500, 'kesalahan server'); });
}).listen(PORT, () => {
  console.log('Server lisensi di :' + PORT);
  console.log('Kunci publik (base64 X.509) untuk APK user -> HM_LICENSE_PUBKEY:\n' + publicB64);
  if (!ADMINS.length) console.warn('PERINGATAN: ADMIN_EMAILS kosong, tidak ada admin yang bisa masuk.');
});

setInterval(() => {
  const n = Date.now();
  for (const [k, v] of otpReqs) if (v.exp < n) otpReqs.delete(k);
  for (const [k, v] of tokens) if (v.exp < n) tokens.delete(k);
  for (const [k, v] of hits) if (n - v.t > 60e3) hits.delete(k);
}, 60e3).unref();
