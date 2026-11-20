// Uji asap: node --test test.js  (menjalankan server sungguhan di port acak, tanpa dependensi)
'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { spawn } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const PORT = 20000 + Math.floor(Math.random() * 20000);
const BASE = `http://127.0.0.1:${PORT}`;
const ADMIN = 'admin@contoh.com', USER = '628123456789', DEV = 'device-aaaa-0001';
let proc, otp = '', token = '', pub;

const call = async (method, p, body, tok) => {
  const r = await fetch(BASE + p, {
    method, headers: { 'Content-Type': 'application/json', ...(tok ? { Authorization: 'Bearer ' + tok } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return { status: r.status, j: await r.json() };
};
const adm = (m, p, b) => call(m, p, b, token);

test.before(async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'lic-'));
  proc = spawn(process.execPath, [path.join(__dirname, 'server.js')], {
    env: { ...process.env, PORT, DATA_DIR: dir, ADMIN_EMAILS: ADMIN, DEV_LOG_CODE: '1', MAX_DEVICES: '2' },
  });
  await new Promise((ok, no) => {
    proc.stdout.on('data', (d) => {
      const s = String(d); const m = /\[DEV\] .*: (\d{6})/.exec(s); if (m) otp = m[1];
      if (s.includes('Server lisensi')) ok();
    });
    proc.on('error', no); setTimeout(() => no(new Error('server tidak naik')), 8000);
  });
  pub = crypto.createPublicKey({ key: Buffer.from((await call('GET', '/license/pubkey')).j.publicKey, 'base64'), format: 'der', type: 'spki' });
});
test.after(() => proc.kill());

const verify = (lic) => crypto.verify('sha256', Buffer.from(lic.payload), pub, Buffer.from(lic.sig, 'base64'));

test('login admin: bukan admin tidak bisa, admin bisa', async () => {
  assert.equal((await adm('GET', '/admin/users')).status, 401);
  const fake = (await call('POST', '/admin/login/send', { email: 'orang@lain.com' })).j;
  assert.ok(fake.ok);   // balasan sama, tapi tidak ada OTP
  assert.equal((await call('POST', '/admin/login/verify', { requestId: fake.requestId, code: '000000' })).status, 400);
  const s = (await call('POST', '/admin/login/send', { email: ADMIN })).j;
  assert.ok(otp);
  assert.equal((await call('POST', '/admin/login/verify', { requestId: s.requestId, code: otp === '111111' ? '222222' : '111111' })).status, 400);
  const v = (await call('POST', '/admin/login/verify', { requestId: s.requestId, code: otp })).j;
  assert.ok(v.token); token = v.token;
});

test('lisensi default Basic, tanda tangan valid, dan rusak bila diubah', async () => {
  const r = (await call('POST', '/license/check', { ident: USER, deviceId: DEV, appVersion: 6 })).j;
  assert.equal(JSON.parse(r.license.payload).tier, 'basic');
  assert.ok(verify(r.license));
  assert.equal(verify({ payload: r.license.payload.replace('basic', 'vip'), sig: r.license.sig }), false);
});

test('admin set paket + masa berlaku + override fitur', async () => {
  const until = Date.now() + 30 * 864e5;
  const u = await adm('PATCH', '/admin/users/' + USER, { tier: 'premium', validUntil: until, featuresAdd: ['CALC_TREND'], featuresRemove: ['EDIT_ROTATE'], msg: 'Halo' });
  assert.equal(u.status, 200);
  const p = JSON.parse((await call('POST', '/license/check', { ident: USER, deviceId: DEV, appVersion: 6 })).j.license.payload);
  assert.equal(p.tier, 'premium'); assert.ok(p.features.includes('CALC_TREND')); assert.ok(!p.features.includes('EDIT_ROTATE'));
  assert.equal(p.msg, 'Halo');
  assert.equal((await adm('PATCH', '/admin/users/' + USER, { tier: 'ngawur' })).status, 400);
});

test('kedaluwarsa turun ke Basic', async () => {
  await adm('PATCH', '/admin/users/' + USER, { validUntil: Date.now() - 1000 });
  const p = JSON.parse((await call('POST', '/license/check', { ident: USER, deviceId: DEV, appVersion: 6 })).j.license.payload);
  assert.equal(p.tier, 'basic');
});

test('block / unblock', async () => {
  await adm('POST', `/admin/users/${USER}/block`);
  let p = JSON.parse((await call('POST', '/license/check', { ident: USER, deviceId: DEV, appVersion: 6 })).j.license.payload);
  assert.equal(p.status, 'blocked'); assert.deepEqual(p.features, []);
  await adm('POST', `/admin/users/${USER}/unblock`);
  p = JSON.parse((await call('POST', '/license/check', { ident: USER, deviceId: DEV, appVersion: 6 })).j.license.payload);
  assert.equal(p.status, 'active');
});

test('batas perangkat dan cabut perangkat', async () => {
  assert.equal((await call('POST', '/license/check', { ident: USER, deviceId: 'device-bbbb-0002', appVersion: 6 })).status, 200);
  const third = await call('POST', '/license/check', { ident: USER, deviceId: 'device-cccc-0003', appVersion: 6 });
  assert.equal(third.status, 403); assert.equal(third.j.code, 'device_limit');
  await adm('DELETE', `/admin/users/${USER}/devices/device-bbbb-0002`);
  assert.equal((await call('POST', '/license/check', { ident: USER, deviceId: 'device-bbbb-0002', appVersion: 6 })).j.code, 'device_revoked');
  assert.equal((await call('POST', '/license/check', { ident: USER, deviceId: 'device-cccc-0003', appVersion: 6 })).status, 200);
});

test('voucher: buat, tukar, tidak bisa dipakai dua kali oleh akun yang sama', async () => {
  const v = (await adm('POST', '/admin/vouchers', { tier: 'platinum', days: 30, maxUse: 5 })).j.voucher;
  assert.match(v.code, /^[A-Z2-9]{4}(-[A-Z2-9]{4}){3}$/);
  const r = await call('POST', '/license/redeem', { ident: USER, deviceId: DEV, code: v.code.toLowerCase() });
  assert.equal(r.status, 200);
  const p = JSON.parse(r.j.license.payload);
  assert.equal(p.tier, 'platinum'); assert.ok(p.validUntil > Date.now() + 29 * 864e5);
  assert.equal((await call('POST', '/license/redeem', { ident: USER, deviceId: DEV, code: v.code })).status, 400);
  assert.equal((await call('POST', '/license/redeem', { ident: USER, deviceId: DEV, code: 'AAAA-AAAA-AAAA-AAAA' })).status, 400);
});

test('log admin tercatat dan rantai hash utuh', async () => {
  const log = (await adm('GET', '/admin/log?limit=500')).j.log.reverse();
  assert.ok(log.length >= 8);
  let prev = '';
  for (const e of log) {
    assert.equal(e.prev, prev);
    assert.equal(e.h, crypto.createHash('sha256').update(prev + JSON.stringify([e.ts, e.admin, e.action, e.target, e.detail])).digest('hex'));
    prev = e.h;
  }
});

test('versi minimum masuk lisensi', async () => {
  assert.equal((await adm('PATCH', '/admin/config', { minVersionCode: 7 })).status, 200);
  const p = JSON.parse((await call('POST', '/license/check', { ident: USER, deviceId: DEV, appVersion: 6 })).j.license.payload);
  assert.equal(p.minVersionCode, 7);
});
