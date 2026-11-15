// OfflineMesh protocol for the web client: byte-compatible with core/src/main/kotlin/mesh/core (Codec, Crypto, Identity, Pow).
// Pure functions, no networking or node state. Works in browsers over plain HTTP (no crypto.subtle) and in Node 22.
// Envelope: { version, type, srcPub, dst, lamport: BigInt, epoch, slot, initTtl, body, powNonce: BigInt, sig, ttl, hops }.
import { p256, sha256, gcm, randomBytes } from './noble.js';

export const C = {
  PROTOCOL_VERSION: 1, MAX_FRAME: 60_000, MAX_POST_BODY: 1_000, MAX_DM_PLAINTEXT: 40_000, MAX_DM_PADDED: 40_960, MAX_NAME: 32,
  TTL_POST: 6, TTL_PRESENCE: 6, TTL_DM: 8, TTL_ACK: 8, MAX_INIT_TTL: 8, EPOCH_SECONDS: 10, SLOTS_PER_EPOCH: 3,
  MAX_AGE_EPOCHS: 360, MAX_FUTURE_EPOCHS: 2, MAX_TREE_IDS: 64, MAX_GROUP: 16,
};
export const EnvType = { PRESENCE: 1, POST: 2, DM: 3, ACK: 4 };
export const FrameType = { ENVELOPE: 0x01, HELLO: 0x10, HELLO_AUTH: 0x11, SYNC_OFFER: 0x12, SYNC_WANT: 0x13, BAN_PROOF: 0x14, IHAVE: 0x15, GRAFT: 0x16, PRUNE: 0x17 };
/** DM plaintext kinds (first byte before U7 padding). */
export const DmKind = { TEXT: 0, PHOTO: 1, GROUP_INVITE: 2, GROUP_TEXT: 3, REVOKE: 4 };
export const BROADCAST = new Uint8Array(8);
export { randomBytes };

const utf8 = new TextEncoder(), utf8d = new TextDecoder();
export const hex = (b) => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
export function unhex(s) {
  if (s.length % 2 || /[^0-9a-f]/i.test(s)) throw new Error('bad hex');
  return Uint8Array.from(s.match(/../g) || [], (h) => parseInt(h, 16));
}
export function concat(...parts) {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let i = 0; for (const p of parts) { out.set(p, i); i += p.length; }
  return out;
}
export const equal = (a, b) => a.length === b.length && a.every((x, i) => x === b[i]);
export const toUtf8 = (s) => utf8.encode(s);
export const fromUtf8 = (b) => utf8d.decode(b);
export const epochOf = (ms) => Math.floor(ms / (C.EPOCH_SECONDS * 1000));

// ---- 5.1 identity: one P-256 key; the public key travels as X.509 SubjectPublicKeyInfo DER (91 bytes), like Java's getEncoded().
const SPKI = unhex('3059301306072a8648ce3d020106082a8648ce3d030107034200');
const pubToDer = (raw65) => concat(SPKI, raw65);
/** Uncompressed point from SPKI DER; throws on anything that is not a P-256 key on the curve. */
function derToPub(der) {
  if (der.length !== 91 || !equal(der.subarray(0, 26), SPKI)) throw new Error('not a P-256 SPKI key');
  const raw = der.subarray(26);
  p256.Point.fromBytes(raw).assertValidity();
  return raw;
}
export const validPub = (der) => { try { derToPub(der); return true; } catch { return false; } };
export const nodeId = (pubDer) => sha256(pubDer).slice(0, 8);
/** "abcd ef01 2345 6789" */
export const fingerprint = (id) => hex(id).match(/..../g).join(' ');

function identityOf(priv) {
  const pub = pubToDer(p256.getPublicKey(priv, false));
  const id = nodeId(pub);
  return { priv, pub, id, idHex: hex(id) };
}
export const createIdentity = () => identityOf(p256.utils.randomSecretKey());
/** JSON-safe form: { priv: 32-byte scalar hex, pub: SPKI DER hex }. */
export const exportIdentity = (me) => ({ priv: hex(me.priv), pub: hex(me.pub) });
export function importIdentity(j) {
  const me = identityOf(unhex(j.priv));
  if (j.pub && j.pub !== hex(me.pub)) throw new Error('public key does not match private key');
  return me;
}

// ---- 5.4 / 5.5 crypto. Signatures are DER SHA256withECDSA like Java; Java may emit high-S, so verify allows it.
export const sign = (priv, data) => p256.sign(data, priv, { format: 'der' });
/** False for any bad key, bad DER or wrong signature; never throws. */
export function verify(pubDer, data, sig) {
  try { return p256.verify(sig, data, derToPub(pubDer), { format: 'der', lowS: false }); } catch { return false; }
}
/** Java's ECDH secret is the shared point's x coordinate. */
export const ecdh = (priv, peerPubDer) => p256.getSharedSecret(priv, derToPub(peerPubDer), true).slice(1);
export const dmKey = (priv, peerPubDer) => sha256(concat(ecdh(priv, peerPubDer), utf8.encode('offlinemesh-dm-v1')));
/** IV || ciphertext || tag. */
export const seal = (key, aad, plain, iv = randomBytes(12)) => concat(iv, gcm(key, iv, aad).encrypt(plain));
/** Null if the body is too short or does not authenticate. */
export function open(key, aad, body) {
  if (body.length < 12 + 16) return null;
  try { return gcm(key, body.subarray(0, 12), aad).decrypt(body.subarray(12)); } catch { return null; }
}

/** U7: kind + data becomes `u8 kind | u16 dataLen | data | zeros` up to 64..4096 (powers of 2) then 4096 multiples; null if too big. */
export function padDm(p) {
  const n = p.length - 1;
  let size = 64;
  while (size < 3 + n && size < 4096) size *= 2;
  if (size < 3 + n) size = Math.ceil((3 + n) / 4096) * 4096;
  if (n < 0 || size > C.MAX_DM_PADDED) return null;
  const out = new Uint8Array(size);
  out[0] = p[0]; out[1] = n >> 8; out[2] = n & 0xff; out.set(p.subarray(1), 3);
  return out;
}
/** U7: back to kind + data; null if dataLen exceeds the bytes there. */
export function unpadDm(x) {
  if (x.length < 3) return null;
  const n = (x[1] << 8) | x[2];
  return n > x.length - 3 ? null : concat(x.subarray(0, 1), x.subarray(3, 3 + n));
}

/** DM body for [plain] (kind + data) from [me] to the peer; AAD is srcId || dstId. Null if too big. */
export function sealDm(me, peerPubDer, plain) {
  const padded = plain.length <= C.MAX_DM_PLAINTEXT ? padDm(plain) : null;
  return padded && seal(dmKey(me.priv, peerPubDer), concat(me.id, nodeId(peerPubDer)), padded);
}
/** Opens a DM envelope with the other side's key (the sender's, or the recipient's for own DMs); kind + data or null. */
export function openDm(me, e, peerPubDer) {
  try {
    const x = open(dmKey(me.priv, peerPubDer), concat(nodeId(e.srcPub), e.dst), e.body);
    const p = x && unpadDm(x);
    return p && p.length ? p : null;
  } catch { return null; }
}

// U8, same as core MeshNode.normName: Cyrillic a e o p c y x i j s d palochka, Greek alpha omicron nu rho tau iota kappa upsilon, then 0 l 1
const LOOK_FROM = 'аеорсухіјѕԁӏαονρτικυ0l1', LOOK_TO = 'aeopcyxijsdiaovptikuoii';
/** U8: name key ignoring case, all spaces, invisible/bidi characters (U+200B, U+202E), width (NFKC) and common look-alikes. */
export const normName = (s) => Array.from(s.normalize('NFKC').toLowerCase().replace(/[\s\x1c-\x1f\p{Cf}]/gu, ''),
  (c) => { const i = LOOK_FROM.indexOf(c); return i < 0 ? c : LOOK_TO[i]; }).join('');

const HELLO_TAG = utf8.encode('offlinemesh-hello-v1');
/** 5.5 step 2: sig over "offlinemesh-hello-v1" || peerNonce || myNodeId || peerNodeId. */
export const helloSign = (me, peerNonce, peerId) => sign(me.priv, concat(HELLO_TAG, peerNonce, me.id, peerId));
/** Checks the peer's HELLO_AUTH: it signed our nonce, its id, then our id. */
export const helloVerify = (peerPubDer, myNonce, myId, sig) =>
  verify(peerPubDer, concat(HELLO_TAG, myNonce, nodeId(peerPubDer), myId), sig);

// ---- 5.3 PoW: SHA-256(core || u64 powNonce) starts with powBits zero bits.
export const powBits = (bodyLen, initTtl) => 14 + Math.min(6, Math.floor(bodyLen / 2048)) + Math.min(4, Math.floor(initTtl / 4));
function zeroBits(h, bits) {
  for (let i = 0; i < bits; i++) if ((h[i >> 3] >> (7 - (i & 7))) & 1) return false;
  return true;
}
const u64 = (n) => { const b = new Uint8Array(8); new DataView(b.buffer).setBigInt64(0, BigInt.asIntN(64, BigInt(n))); return b; };
/** Smallest nonce from 0 up (same answer as Kotlin's Pow.mine). Pure and synchronous, so a Web Worker can run it. */
export function powMine(core, bits) {
  const prefix = sha256.create().update(core);
  const nb = new Uint8Array(8), dv = new DataView(nb.buffer);
  for (let n = 0; ; n++) {
    dv.setUint32(0, Math.floor(n / 2 ** 32)); dv.setUint32(4, n >>> 0);
    if (zeroBits(prefix.clone().update(nb).digest(), bits)) return BigInt(n);
  }
}
export const powVerify = (e) => zeroBits(sha256(concat(encodeCore(e), u64(e.powNonce))), powBits(e.body.length, e.initTtl));

// ---- 5.2 / 5.3 codec. Decoders are strict: wrong lengths, unknown types or trailing bytes give null.
class W {
  constructor() { this.parts = []; }
  u8(v) { if (!(v >= 0 && v <= 255)) throw new Error('u8 out of range'); this.parts.push(Uint8Array.of(v)); return this; }
  u16(v) { if (!(v >= 0 && v <= 0xffff)) throw new Error('u16 out of range'); this.parts.push(Uint8Array.of(v >> 8, v & 0xff)); return this; }
  i64(v) { this.parts.push(u64(v)); return this; }
  u32(v) { const b = new Uint8Array(4); new DataView(b.buffer).setUint32(0, v >>> 0); this.parts.push(b); return this; }
  raw(b) { this.parts.push(b); return this; }
  u8Bytes(b) { return this.u8(b.length).raw(b); }
  id(b) { if (b.length !== 8) throw new Error('id must be 8 bytes'); return this.raw(b); }
  ids(ids) { this.u16(ids.length); ids.forEach((i) => this.id(i)); return this; }
  bytes() { return concat(...this.parts); }
}
class Bad extends Error {}
class R {
  constructor(b) { this.b = b; this.i = 0; this.dv = new DataView(b.buffer, b.byteOffset, b.length); }
  need(ok) { if (!ok) throw new Bad(); }
  take(n) { this.need(this.i + n <= this.b.length); return this.b.slice(this.i, (this.i += n)); }
  u8() { this.need(this.i < this.b.length); return this.b[this.i++]; }
  u16() { const v = this.take(2); return (v[0] << 8) | v[1]; }
  u32() { this.need(this.i + 4 <= this.b.length); const v = this.dv.getUint32(this.i); this.i += 4; return v; }
  i64() { this.need(this.i + 8 <= this.b.length); const v = this.dv.getBigInt64(this.i); this.i += 8; return v; }
}
function parse(b, f) {
  try { const r = new R(b); const v = f(r); return v != null && r.i === b.length ? v : null; }
  catch (e) { if (e instanceof Bad) return null; throw e; }
}

export function encodeCore(e) {
  if (e.body.length > 0xffff) throw new Error('body too large');
  return new W().u8(e.version).u8(e.type).u8Bytes(e.srcPub).id(e.dst).i64(e.lamport).u32(e.epoch)
    .u8(e.slot).u8(e.initTtl).u16(e.body.length).raw(e.body).bytes();
}
export const encodeEnvelope = (e) => concat(encodeCore(e), new W().i64(e.powNonce).u8Bytes(e.sig).u8(e.ttl).u8(e.hops).bytes());
export const msgId = (e) => sha256(encodeCore(e)).slice(0, 8);
export const decodeEnvelope = (b) => parse(b, readEnvelope);
export const isBroadcast = (e) => e.dst.every((x) => x === 0);

function bodyOk(type, body) {
  switch (type) {
    case EnvType.PRESENCE: return body.length > 0 && body[0] <= C.MAX_NAME && body.length === 1 + body[0];
    case EnvType.POST: return body.length >= 1 && body.length <= C.MAX_POST_BODY;
    case EnvType.DM: return body.length >= 12 + 1 + 16 && body.length <= 12 + C.MAX_DM_PADDED + 16;
    case EnvType.ACK: return body.length === 8;
    default: return false;
  }
}
function readEnvelope(r) {
  const version = r.u8(), type = r.u8(), srcPub = r.take(r.u8()), dst = r.take(8);
  const lamport = r.i64(), epoch = r.u32(), slot = r.u8(), initTtl = r.u8(), body = r.take(r.u16());
  const powNonce = r.i64(), sig = r.take(r.u8()), ttl = r.u8(), hops = r.u8();
  r.need(srcPub.length > 0 && sig.length > 0 && bodyOk(type, body));
  r.need(powNonce === 0n || type === EnvType.POST || type === EnvType.DM);
  return { version, type, srcPub, dst, lamport, epoch, slot, initTtl, body, powNonce, sig, ttl, hops };
}

/**
 * Origin side: mines PoW for POST/DM (or uses opts.nonce, e.g. from a Worker running powMine(encodeCore(e), powBits(...)))
 * and signs the core. lamport may be a number or BigInt.
 */
export function createEnvelope(me, type, dst, lamport, epoch, slot, initTtl, body, opts = {}) {
  const e = { version: C.PROTOCOL_VERSION, type, srcPub: me.pub, dst, lamport: BigInt(lamport), epoch, slot, initTtl, body,
    powNonce: 0n, sig: new Uint8Array(0), ttl: initTtl, hops: 0 };
  const core = encodeCore(e);
  if (type === EnvType.POST || type === EnvType.DM) e.powNonce = opts.nonce != null ? BigInt(opts.nonce) : powMine(core, powBits(body.length, initTtl));
  e.sig = sign(me.priv, core);
  return e;
}

/** Stateless 5.6 checks in pipeline order (sane, PoW, signature): null if fine, else the failing check. Lamport, freshness, rate and ledger are node state. */
export function checkEnvelope(e) {
  if (e.version !== C.PROTOCOL_VERSION || e.ttl + e.hops !== e.initTtl || e.initTtl > C.MAX_INIT_TTL) return 'MALFORMED';
  if ((e.type === EnvType.POST || e.type === EnvType.DM) && !powVerify(e)) return 'BAD_POW';
  if (!verify(e.srcPub, encodeCore(e), e.sig)) return 'BAD_SIGNATURE';
  return null;
}
/** The copy a relay forwards: one hop more, one TTL less. */
export const relayed = (e) => ({ ...e, ttl: e.ttl - 1, hops: e.hops + 1 });

// Body builders for the four envelope types.
/** PRESENCE body: u8 len | name UTF-8, cut to MAX_NAME bytes. */
export function presenceBody(name) {
  let b = utf8.encode(name);
  while (b.length > C.MAX_NAME) b = utf8.encode((name = Array.from(name).slice(0, -1).join('')));
  return concat(Uint8Array.of(b.length), b);
}
export const presenceName = (e) => fromUtf8(e.body.subarray(1, 1 + e.body[0]));
/** ACK for a DM addressed to me: dst = the DM's sender, body = its msgId. */
export const createAck = (me, dm, lamport, epoch) => createEnvelope(me, EnvType.ACK, nodeId(dm.srcPub), lamport, epoch, 0, C.TTL_ACK, msgId(dm));

/**
 * Frames: { t: 'env', env } | { t: 'hello', pub, nonce } | { t: 'helloAuth', sig } | { t: 'syncOffer', ids } | { t: 'syncWant', ids }
 * | { t: 'banProof', a, b } | { t: 'ihave', ids, hops } | { t: 'graft', ids } | { t: 'prune' }. ids are 8-byte Uint8Arrays.
 */
export function encodeFrame(f) {
  const w = new W();
  const tree = (ids) => { if (ids.length < 1 || ids.length > C.MAX_TREE_IDS) throw new Error('1..64 ids'); };
  switch (f.t) {
    case 'env': w.u8(FrameType.ENVELOPE).raw(encodeEnvelope(f.env)); break;
    case 'hello': if (f.nonce.length !== 16) throw new Error('nonce must be 16 bytes'); w.u8(FrameType.HELLO).u8Bytes(f.pub).raw(f.nonce); break;
    case 'helloAuth': w.u8(FrameType.HELLO_AUTH).u8Bytes(f.sig); break;
    case 'syncOffer': w.u8(FrameType.SYNC_OFFER).ids(f.ids); break;
    case 'syncWant': w.u8(FrameType.SYNC_WANT).ids(f.ids); break;
    case 'banProof': w.u8(FrameType.BAN_PROOF); for (const e of [f.a, f.b]) { const x = encodeEnvelope(e); w.u16(x.length).raw(x); } break;
    case 'ihave':
      tree(f.ids); if (f.hops.length !== f.ids.length) throw new Error('one hop count per id');
      w.u8(FrameType.IHAVE).u16(f.ids.length); f.ids.forEach((id, i) => w.id(id).u8(Math.max(0, Math.min(255, f.hops[i])))); break;
    case 'graft': tree(f.ids); w.u8(FrameType.GRAFT).ids(f.ids); break;
    case 'prune': w.u8(FrameType.PRUNE); break;
    default: throw new Error('unknown frame ' + f.t);
  }
  const out = w.bytes();
  if (out.length > C.MAX_FRAME) throw new Error('frame too large: ' + out.length);
  return out;
}

export function decodeFrame(b) {
  if (b.length < 1 || b.length > C.MAX_FRAME) return null;
  return parse(b, (r) => {
    const list = (n) => Array.from({ length: n }, () => r.take(8));
    const treeN = () => { const n = r.u16(); r.need(n >= 1 && n <= C.MAX_TREE_IDS); return n; };
    const proofPost = () => { const e = decodeEnvelope(r.take(r.u16())); r.need(e && e.type === EnvType.POST); return e; };
    switch (r.u8()) {
      case FrameType.ENVELOPE: return { t: 'env', env: readEnvelope(r) };
      case FrameType.HELLO: { const n = r.u8(); r.need(n > 0); return { t: 'hello', pub: r.take(n), nonce: r.take(16) }; }
      case FrameType.HELLO_AUTH: { const n = r.u8(); r.need(n > 0); return { t: 'helloAuth', sig: r.take(n) }; }
      case FrameType.SYNC_OFFER: return { t: 'syncOffer', ids: list(r.u16()) };
      case FrameType.SYNC_WANT: return { t: 'syncWant', ids: list(r.u16()) };
      case FrameType.BAN_PROOF: return { t: 'banProof', a: proofPost(), b: proofPost() };
      case FrameType.IHAVE: {
        const ids = [], hops = [];
        for (let n = treeN(); n > 0; n--) { ids.push(r.take(8)); hops.push(r.u8()); }
        return { t: 'ihave', ids, hops };
      }
      case FrameType.GRAFT: return { t: 'graft', ids: list(treeN()) };
      case FrameType.PRUNE: return { t: 'prune' };
      default: return null;
    }
  });
}

// ---- DM plaintexts (kind + data, before padding). 13.2 groups are fan-out DMs of kinds 2 and 3.
export const textPlain = (text) => concat(Uint8Array.of(DmKind.TEXT), utf8.encode(text));
export const revokePlain = () => Uint8Array.of(DmKind.REVOKE);
export const isJpeg = (b) => b.length >= 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff;
/** The groupId is bound to its owner: SHA-256(ownerId || salt)[0..8]. */
export const gidOf = (ownerId, salt) => sha256(concat(ownerId, salt)).slice(0, 8);
/** kind 3: gid(8) | groupMsgId(8) | text. */
export const groupTextPlain = (gid, gm, text) => concat(Uint8Array.of(DmKind.GROUP_TEXT), gid, gm, utf8.encode(text));
/** kind 2: gid(8) | salt(8) | u8 nameLen | name | u8 n | n x (u8 len | SPKI pub). Members start with the owner. */
export function invitePlain(gid, salt, name, memberPubs) {
  const w = new W().u8(DmKind.GROUP_INVITE).id(gid).id(salt).u8Bytes(presenceBody(name).subarray(1)).u8(memberPubs.length);
  memberPubs.forEach((p) => w.u8Bytes(p));
  return w.bytes();
}
/**
 * Parses an opened DM plaintext; null if malformed (Kotlin does not store or ACK those).
 * { kind: 0, text } | { kind: 1, jpeg } | { kind: 2, gid, salt, name, members: [pub] } | { kind: 3, gid, gm, text } | { kind: 4 }.
 * Group policy (gid == gidOf(src, salt), src a member, owner unchanged) is the node's job.
 */
export function parsePlain(p) {
  if (!p || !p.length) return null;
  switch (p[0]) {
    case DmKind.TEXT: return { kind: 0, text: fromUtf8(p.subarray(1)) };
    case DmKind.PHOTO: return isJpeg(p.subarray(1)) ? { kind: 1, jpeg: p.slice(1) } : null;
    case DmKind.GROUP_INVITE: return parse(p, (r) => {
      r.u8();
      const gid = r.take(8), salt = r.take(8), nl = r.u8();
      r.need(nl <= C.MAX_NAME);
      const name = fromUtf8(r.take(nl)), n = r.u8();
      r.need(n >= 1 && n <= C.MAX_GROUP);
      const members = Array.from({ length: n }, () => { const k = r.take(r.u8()); r.need(validPub(k)); return k; });
      return { kind: 2, gid, salt, name, members };
    });
    case DmKind.GROUP_TEXT:
      return p.length < 18 || p.length - 17 > C.MAX_POST_BODY ? null : { kind: 3, gid: p.slice(1, 9), gm: p.slice(9, 17), text: fromUtf8(p.subarray(17)) };
    case DmKind.REVOKE: return p.length === 1 ? { kind: 4 } : null;
    default: return null;
  }
}
