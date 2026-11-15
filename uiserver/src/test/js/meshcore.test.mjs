// meshcore.js tests, and the cross-check with the Kotlin core in both directions. One command, from the repo root:
//   node --test uiserver/src/test/js/meshcore.test.mjs
// It writes core/build/web-vectors/js.json (frames made here), runs `gradlew :core:test --tests mesh.core.WebVectorsTest --rerun`
// (Kotlin checks js.json and writes kotlin.json), then checks kotlin.json here. SKIP_GRADLE=1 runs the JS-only tests.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import * as m from '../../main/resources/web/meshcore.js';
import { createWebNode } from '../../main/resources/web/meshnode.js';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../..');
const vecDir = path.join(root, 'core/build/web-vectors');
const ep = m.epochOf(Date.now());
const pattern = (n) => Uint8Array.from({ length: n + 1 }, (_, i) => (i * 7) & 0xff);
const roundTrip = (f) => { const b = m.encodeFrame(f); assert.deepEqual(m.encodeFrame(m.decodeFrame(b)), b); return m.decodeFrame(b); };

// ---- JS only ----
test('normName: same cases as core RedTeamTest T7', () => {
  // case, spaces, RTL override, zero-width space, Cyrillic A, fullwidth letters, capital I for l
  for (const n of ['alice', 'Alice ', ' ALICE', 'Ali ce', '\u202eAlice', 'Ali\u200bce', '\u0410lice', '\uff21\uff4c\uff49\uff43\uff45', 'AIice'])
    assert.equal(m.normName(n), m.normName('Alice'), JSON.stringify(n));
  assert.notEqual(m.normName('Bob'), m.normName('Alice'));
});

test('T3: a handshake frame on a bound link closes it', () => {
  for (const late of [(h) => ({ t: 'hello', pub: h.pub, nonce: m.randomBytes(16) }), () => ({ t: 'helloAuth', sig: new Uint8Array(64).fill(1) })]) {
    let sock = null; const sent = [];
    class FakeWS { constructor() { sock = this; this.readyState = 1; } send(b) { sent.push(m.decodeFrame(b)); } close() { this.readyState = 3; this.onclose?.(); } }
    const web = createWebNode({ url: 'ws://x/mesh', storage: new Map(), WebSocket: FakeWS });
    const host = m.createIdentity(), push = (f) => sock.onmessage({ data: m.encodeFrame(f).slice().buffer });
    sock.onopen();
    const hello = sent.find((f) => f.t === 'hello');
    push({ t: 'hello', pub: host.pub, nonce: m.randomBytes(16) });
    push({ t: 'helloAuth', sig: m.helloSign(host, hello.nonce, m.nodeId(hello.pub)) });
    assert.ok(web.connected, 'handshake did not complete');
    push(late(host));
    assert.equal(web.connected, false, `${late(host).t} on a bound link was ignored`);
    assert.ok(web.snapshot().security.some((e) => e.kind === 'LINK_CLOSED' && e.detail === 'handshake frame on a bound link'));
    web.stop();
  }
});

test('identity export/import', () => {
  const a = m.createIdentity();
  const b = m.importIdentity(JSON.parse(JSON.stringify(m.exportIdentity(a))));
  assert.equal(b.idHex, a.idHex); assert.equal(a.pub.length, 91);
  assert.throws(() => m.importIdentity({ ...m.exportIdentity(a), pub: m.hex(m.createIdentity().pub) }));
});

test('envelopes: sign, PoW, tamper, relay', () => {
  const a = m.createIdentity();
  const e = roundTrip({ t: 'env', env: m.createEnvelope(a, m.EnvType.POST, m.BROADCAST, 1, ep, 0, 6, m.toUtf8('hi')) }).env;
  assert.equal(m.checkEnvelope(e), null);
  assert.equal(m.checkEnvelope(m.relayed(e)), null);
  assert.deepEqual(m.msgId(m.relayed(e)), m.msgId(e));
  assert.equal(m.checkEnvelope({ ...e, body: m.toUtf8('ho') }), 'BAD_POW');
  assert.equal(m.checkEnvelope({ ...e, ttl: 5 }), 'MALFORMED');
  const unmined = { ...e, lamport: 2n };
  unmined.powNonce = m.powMine(m.encodeCore(unmined), m.powBits(2, 6));
  assert.equal(m.checkEnvelope(unmined), 'BAD_SIGNATURE');
  const p = roundTrip({ t: 'env', env: m.createEnvelope(a, m.EnvType.PRESENCE, m.BROADCAST, 3, ep, 0, 6, m.presenceBody('x'.repeat(40))) }).env;
  assert.equal(m.presenceName(p), 'x'.repeat(32)); assert.equal(p.powNonce, 0n);
  assert.equal(m.presenceBody('é'.repeat(20)).length, 1 + 32);
});

test('strict decoder', () => {
  const a = m.createIdentity();
  const good = m.encodeFrame({ t: 'env', env: m.createEnvelope(a, m.EnvType.ACK, m.BROADCAST, 1, ep, 0, 8, new Uint8Array(8)) });
  assert.ok(m.decodeFrame(good));
  assert.equal(m.decodeFrame(m.concat(good, Uint8Array.of(0))), null);   // trailing byte
  assert.equal(m.decodeFrame(good.subarray(0, good.length - 1)), null); // truncated
  assert.equal(m.decodeFrame(Uint8Array.of(0x99)), null);
  assert.equal(m.decodeFrame(new Uint8Array(0)), null);
  assert.equal(m.decodeFrame(Uint8Array.of(0x17, 0)), null);
  assert.equal(m.decodeFrame(Uint8Array.of(0x16, 0, 0)), null);          // GRAFT needs 1..64 ids
  assert.equal(m.decodeFrame(Uint8Array.of(0x11, 0)), null);             // empty HELLO_AUTH sig
});

test('every frame type round-trips', () => {
  const a = m.createIdentity(), ids = [m.randomBytes(8), m.randomBytes(8)];
  roundTrip({ t: 'hello', pub: a.pub, nonce: m.randomBytes(16) });
  roundTrip({ t: 'helloAuth', sig: m.helloSign(a, m.randomBytes(16), ids[0]) });
  for (const t of ['syncOffer', 'syncWant', 'graft']) assert.equal(roundTrip({ t, ids }).ids.length, 2);
  assert.equal(roundTrip({ t: 'syncWant', ids: [] }).ids.length, 0);
  assert.deepEqual(roundTrip({ t: 'ihave', ids, hops: [0, 300] }).hops, [0, 255]);
  roundTrip({ t: 'prune' });
  const p = (s) => m.createEnvelope(a, m.EnvType.POST, m.BROADCAST, 1, ep, 0, 6, m.toUtf8(s));
  roundTrip({ t: 'banProof', a: p('one'), b: p('two') });
});

test('DM: seal/open, padding, plaintext kinds', () => {
  const a = m.createIdentity(), b = m.createIdentity();
  const send = (plain) => m.createEnvelope(a, m.EnvType.DM, b.id, 1, ep, 0, 8, m.sealDm(a, b.pub, plain));
  const e = roundTrip({ t: 'env', env: send(m.textPlain('hey')) }).env;
  assert.equal(e.body.length, 12 + 64 + 16);
  assert.equal(m.parsePlain(m.openDm(b, e, a.pub)).text, 'hey');
  assert.equal(m.parsePlain(m.openDm(a, e, b.pub)).text, 'hey');      // own DM, opened with the recipient's key
  assert.equal(m.openDm(m.createIdentity(), e, a.pub), null);
  assert.equal(m.sealDm(a, b.pub, new Uint8Array(C_MAX + 1)), null);
  const salt = m.randomBytes(8), gid = m.gidOf(a.id, salt);
  const inv = m.parsePlain(m.invitePlain(gid, salt, 'team', [a.pub, b.pub]));
  assert.deepEqual([inv.kind, inv.name, inv.members.length], [2, 'team', 2]);
  assert.deepEqual(inv.gid, gid);
  assert.equal(m.parsePlain(m.concat(m.invitePlain(gid, salt, 'team', [a.pub]), Uint8Array.of(1))), null);
  const gt = m.parsePlain(m.groupTextPlain(gid, Uint8Array.of(1, 2, 3, 4, 5, 6, 7, 8), 'yo'));
  assert.deepEqual([gt.kind, gt.text], [3, 'yo']);
  assert.equal(m.parsePlain(m.revokePlain()).kind, 4);
  assert.equal(m.parsePlain(Uint8Array.of(1, 0, 0)), null);           // photo that is not a JPEG
  assert.equal(m.unpadDm(Uint8Array.of(0, 0, 9, 1)), null);
});
const C_MAX = m.C.MAX_DM_PLAINTEXT;

// ---- cross-check with Kotlin ----
test('cross-check with Kotlin, both directions', { skip: !!process.env.SKIP_GRADLE }, () => {
  const c = m.createIdentity(), d = m.createIdentity();
  const hexF = (f) => m.hex(m.encodeFrame(f)), envF = (e) => hexF({ t: 'env', env: e });
  const v = { 'c.priv': m.hex(c.priv), 'c.pub': m.hex(c.pub), 'c.id': c.idHex, 'd.priv': m.hex(d.priv), 'd.pub': m.hex(d.pub) };
  const post = m.createEnvelope(c, m.EnvType.POST, m.BROADCAST, 7, ep, 2, 6, m.toUtf8('hello from js'));
  Object.assign(v, { 'post.frame': envF(post), 'post.msgId': m.hex(m.msgId(post)), 'post.text': 'hello from js' });
  v['presence.frame'] = envF(m.createEnvelope(c, m.EnvType.PRESENCE, m.BROADCAST, 8, ep, 0, 6, m.presenceBody('carol')));
  const plain = m.textPlain('secret über js');
  const dm = m.createEnvelope(c, m.EnvType.DM, d.id, 9, ep, 0, 8, m.sealDm(c, d.pub, plain));
  Object.assign(v, { 'dm.frame': envF(dm), 'dm.msgId': m.hex(m.msgId(dm)), 'dm.plain': m.hex(plain) });
  v['ack.frame'] = envF(m.createAck(d, dm, 2, ep));
  v.dmKey = m.hex(m.dmKey(d.priv, c.pub));
  const nonce = m.randomBytes(16);
  v['hello.frame'] = hexF({ t: 'hello', pub: c.pub, nonce });
  v['helloAuth.peerNonce'] = m.hex(nonce);
  v['helloAuth.frame'] = hexF({ t: 'helloAuth', sig: m.helloSign(c, nonce, d.id) });
  const ids = [m.msgId(post), m.msgId(dm)];
  for (const t of ['syncOffer', 'syncWant', 'graft']) v[`${t}.frame`] = hexF({ t, ids });
  v['ihave.frame'] = hexF({ t: 'ihave', ids, hops: [1, 2] });
  v['prune.frame'] = hexF({ t: 'prune' });
  v['banProof.frame'] = hexF({ t: 'banProof', a: post, b: m.createEnvelope(c, m.EnvType.POST, m.BROADCAST, 10, ep, 2, 6, m.toUtf8('dup')) });
  const core = m.encodeCore(post);
  Object.assign(v, { 'pow.core': m.hex(core), 'pow.bits': '17', 'pow.nonce': String(m.powMine(core, 17)) });
  for (const n of [0, 61, 62, 4093, 4094, 39999]) v[`pad.${n}`] = m.hex(m.padDm(pattern(n)));
  mkdirSync(vecDir, { recursive: true });
  writeFileSync(path.join(vecDir, 'js.json'), JSON.stringify(v));
  const win = process.platform === 'win32';
  // Absolute path: the cwd may not be on the Windows command search path (NoDefaultCurrentDirectoryInExePath).
  const r = spawnSync(win ? `"${path.join(root, 'gradlew.bat')}"` : './gradlew', [':core:test', '--tests', 'mesh.core.WebVectorsTest', '--rerun', '-q'],
    { cwd: root, shell: win, encoding: 'utf8' });
  assert.equal(r.status, 0, 'Kotlin WebVectorsTest failed (it checks js.json):\n' + (r.stdout + r.stderr).slice(-4000));

  checkKotlin(JSON.parse(readFileSync(path.join(vecDir, 'kotlin.json'), 'utf8')));
});

/** kotlin.json: frames, signatures, PoW, DM, ACK, HELLO made by the Kotlin core. */
function checkKotlin(kv) {
  const a = m.importIdentity({ priv: kv['a.priv'], pub: kv['a.pub'] }), b = m.importIdentity({ priv: kv['b.priv'], pub: kv['b.pub'] });
  const frame = (k) => { const x = m.unhex(kv[k]); const f = m.decodeFrame(x); assert.ok(f, k); assert.equal(m.hex(m.encodeFrame(f)), kv[k], k); return f; };
  for (const k of Object.keys(kv)) if (k.endsWith('.frame')) frame(k);
  const post = frame('post.frame').env;
  assert.equal(m.checkEnvelope(post), null);
  assert.equal(m.hex(m.msgId(post)), kv['post.msgId']);
  assert.equal(m.fromUtf8(post.body), kv['post.text']);
  assert.equal(m.presenceName(frame('presence.frame').env), 'alice');
  assert.equal(m.checkEnvelope(frame('presence.frame').env), null);
  for (const k of ['dm.frame', 'dmRelayed.frame']) {
    const dm = frame(k).env;
    assert.equal(m.checkEnvelope(dm), null, k);
    assert.equal(m.hex(m.msgId(dm)), kv['dm.msgId'], k);
    assert.equal(m.hex(m.openDm(b, dm, a.pub)), kv['dm.plain'], k);
    assert.equal(m.parsePlain(m.openDm(a, dm, b.pub)).text, 'secret über kotlin');
  }
  const ack = frame('ack.frame').env;
  assert.equal(m.checkEnvelope(ack), null);
  assert.equal(m.hex(ack.body), kv['dm.msgId']); assert.deepEqual(ack.dst, a.id);
  assert.equal(m.hex(m.dmKey(a.priv, b.pub)), kv.dmKey); assert.equal(m.hex(m.dmKey(b.priv, a.pub)), kv.dmKey);
  const hello = frame('hello.frame');
  assert.deepEqual(hello.pub, a.pub);
  assert.ok(m.helloVerify(a.pub, m.unhex(kv['helloAuth.peerNonce']), b.id, frame('helloAuth.frame').sig));
  assert.ok(!m.helloVerify(a.pub, m.unhex(kv['helloAuth.peerNonce']), a.id, frame('helloAuth.frame').sig));
  assert.deepEqual(frame('syncWant.frame').ids.map(m.hex), [kv['post.msgId'], kv['dm.msgId']]);
  assert.equal(frame('syncWantEmpty.frame').ids.length, 0);
  assert.deepEqual(frame('ihave.frame').hops, [0, 3]);
  const bp = frame('banProof.frame');
  assert.equal(bp.a.slot, bp.b.slot); assert.equal(m.checkEnvelope(bp.b), null);
  assert.equal(String(m.powMine(m.unhex(kv['pow.core']), Number(kv['pow.bits']))), kv['pow.nonce']);
  for (const k of Object.keys(kv)) if (k.startsWith('pad.')) assert.equal(m.hex(m.padDm(pattern(Number(k.slice(4))))), kv[k], k);
}
