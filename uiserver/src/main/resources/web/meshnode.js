// OfflineMesh leaf node: one WebSocket link to a host node's GuestServer /mesh, never relays (MeshNode.kt for a one-link node).
// Speaks the UiServer JSON protocol, so index.html can swap backends: onEvent gets the same events (snapshot, msg, status, net,
// sec, group, gstatus, stats) plus { t: 'error', cmd, error }, and cmd() takes the same commands.
//   const node = createWebNode({ url: 'ws://host:port/mesh?pin=1234', storage: localStorage, powMine, WebSocket });
//   const off = node.onEvent((ev) => ...); node.cmd({ t: 'post', text: 'hi' }); node.stop();
// storage: { getItem|get(key) -> string|null, setItem|set(key, string) }. powMine(core, bits) -> Promise<nonce> (a Web Worker
// running meshcore.powMine in browsers); default runs meshcore.powMine inline. Persists identity, lamport, the last 200 messages,
// contacts, groups and own unACKed envelopes. Refused (error event): debug, attack, profile.
import * as M from './meshcore.js';

const { hex, unhex } = M;
const T = M.EnvType;
const EPOCH_MS = M.C.EPOCH_SECONDS * 1000, PRESENCE_MS = 30_000, SYNC_MS = 15_000, ROUTE_MS = 120_000, SILENT_MS = 45_000;
const KEEP = 200, SEEN_CAP = 4096, OUT_CAP = 256, MAX_CONTACTS = 256, MAX_GROUPS = 32, PER_OWNER = 4, MAX_WANT = 512;
const SEND_BUCKET = 25, BUCKET_MS = 10_000, REVOKE_WAIT_MS = 3_000, PHOTO = '(photo)';
const NODE_ID = /^[0-9a-f]{16}$/;
const KEY = 'offlinemesh.webnode';
const SEC_KINDS = ['MALFORMED', 'BAD_POW', 'BAD_SIGNATURE', 'STALE', 'RATE_EQUIVOCATION', 'LINK_FLOOD', 'LINK_CLOSED', 'BANNED', 'ROUTE_FAILED', 'NAME_IMPERSONATION', 'PEER_REVOKED'];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const b64 = (b) => { let s = ''; for (let i = 0; i < b.length; i += 8192) s += String.fromCharCode(...b.subarray(i, i + 8192)); return btoa(s); };
const unb64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
const msg = (m) => ({ id: m.id, from: m.from, to: m.to ?? null, text: m.text, lamport: m.lamport, tsMs: m.tsMs, mine: m.mine, status: m.status,
  hops: m.hops ?? 0, via: m.via ?? null, img: m.img ?? null, group: m.group ?? null, delivered: m.delivered ?? null, total: m.total ?? null, rerouteVia: null });

export function createWebNode({ url, storage, powMine, WebSocket: WS = globalThis.WebSocket, now = Date.now }) {
  const get = (k) => (storage.getItem ? storage.getItem(k) : storage.get(k)) ?? null;
  const set = (k, v) => (storage.setItem ? storage.setItem(k, v) : storage.set(k, v));
  const pow = powMine || (async (core, bits) => M.powMine(core, bits));
  const listeners = new Set(), timers = new Set();
  let me, name, lamport, startEpoch, postEpoch, postSlots, gen = 0;
  let contacts, msgs, groups, outbox, seen, verified, muted, revoked, ledger, lastPresence, bans, security, stats;
  let ws = null, conn = 0, authed = false, host = null, hostPub = null, myNonce = null, lastRx = 0, backoff = 1000, stopped = false;
  const wanted = new Set(), outQ = [];
  let tokens = SEND_BUCKET, tokAt = now(), drainT = null, saveT = null, powChain = Promise.resolve();
  const rtts = [];

  const epochNow = () => Math.floor(now() / EPOCH_MS);
  const later = (ms, fn) => { const g = gen; const t = setTimeout(() => { timers.delete(t); if (!stopped && g === gen) fn(); }, ms); timers.add(t); return t; };
  const emit = (ev) => { const s = JSON.parse(JSON.stringify(ev)); for (const l of listeners) try { l(s); } catch (e) { console.error(e); } };
  const emitNet = () => emit({ t: 'net', contacts: contactList(), links: linkList() });
  function fitName(s, blank) {
    let n = s.trim() ? s : blank;
    while (M.toUtf8(n).length > M.C.MAX_NAME) n = Array.from(n).slice(0, -1).join('');
    return n;
  }

  // ---- state and storage ----

  function reset(ident) {
    me = ident; name = `web-${me.idHex.slice(0, 4)}`; lamport = 0; startEpoch = epochNow(); postEpoch = -1; postSlots = 0;
    contacts = new Map(); msgs = new Map(); groups = new Map(); outbox = new Map(); seen = new Map();   // seen: mid -> { type, dst, acked, ack }
    verified = new Set(); muted = new Set(); revoked = new Set(); ledger = new Map(); lastPresence = new Map(); bans = new Map(); security = [];
    stats = { originated: 0, relayed: 0, duplicatesDropped: 0, forwardsSkipped: 0, bytesOut: 0, bytesIn: 0, ackRttMsLast: 0, ackRttMsMedian: 0,
      powMsLast: 0, syncRounds: 0, syncRecovered: 0, linkUps: 0, linkDowns: 0, fullSends: 0, ihaveIds: 0, grafts: 0, prunes: 0,
      drops: Object.fromEntries(SEC_KINDS.map((k) => [k, 0])) };
    rtts.length = 0;
  }

  function load() {
    let s = null;
    try { s = JSON.parse(get(KEY) || 'null'); } catch { s = null; }
    let ident = null;
    try { ident = s?.identity && M.importIdentity(s.identity); } catch { ident = null; }
    reset(ident || M.createIdentity());
    if (!ident) return save(true);
    name = fitName(s.name || '', name);
    lamport = Number(s.lamport) || 0;
    for (const c of s.contacts || []) contacts.set(c.id, { ...c, pub: unhex(c.pub), hops: null, heardAt: 0 });
    for (const m of s.msgs || []) msgs.set(m.id, msg(m));
    for (const g of s.groups || []) groups.set(g.id, { ...g, salt: unhex(g.salt), members: new Map(g.members.map(([i, p]) => [i, unhex(p)])) });
    for (const o of s.outbox || []) { const e = M.decodeEnvelope(unhex(o.env)); if (e) addOut(e, o.gm).sent = !!o.sent; }
    for (const x of s.verified || []) verified.add(x);
    for (const x of s.muted || []) muted.add(x);
    for (const x of s.revoked || []) revoked.add(x);
  }

  function save(nowToo) {
    if (nowToo) return flush();
    if (!saveT) saveT = setTimeout(flush, 300);
  }
  function flush() {
    clearTimeout(saveT); saveT = null;
    const s = {
      identity: M.exportIdentity(me), name, lamport,
      contacts: [...contacts].map(([id, c]) => ({ id, name: c.name, pub: hex(c.pub), lastSeen: c.lastSeen, lamport: c.lamport })),
      msgs: [...msgs.values()],
      groups: [...groups.values()].map((g) => ({ id: g.id, name: g.name, owner: g.owner, salt: hex(g.salt), members: [...g.members].map(([i, p]) => [i, hex(p)]) })),
      outbox: [...outbox.values()].map((o) => ({ env: hex(M.encodeEnvelope(o.env)), gm: o.gm, sent: o.sent })),
      verified: [...verified], muted: [...muted], revoked: [...revoked],
    };
    try { set(KEY, JSON.stringify(s)); } catch {
      // shortcut: over quota (photos are ~50 KB each) the saved copy drops photo data; the live session keeps it.
      try { set(KEY, JSON.stringify({ ...s, msgs: s.msgs.map((m) => (m.img ? { ...m, img: null } : m)) })); } catch (e) { console.error('webnode: save failed', e); }
    }
  }

  function remember(mid, entry) {
    seen.set(mid, entry);
    if (seen.size > SEEN_CAP) seen.delete(seen.keys().next().value);
    return entry;
  }

  /** Own POST/DM kept to push on connect and serve SYNC_WANT until ACKed (DMs) or too old to offer (POSTs). [gm]: see originate. */
  function addOut(e, gm) {
    const mid = hex(M.msgId(e)), o = { env: e, gm: gm ?? null, sent: false };
    outbox.set(mid, o);
    if (!seen.has(mid)) remember(mid, { type: e.type, dst: M.isBroadcast(e) ? null : hex(e.dst) });
    if (outbox.size > OUT_CAP) {
      const old = [...outbox].find(([, o]) => o.env.type === T.POST) || outbox.entries().next().value;
      outbox.delete(old[0]);
    }
    return o;
  }

  function addMsg(m, emitIt = true) {
    msgs.set(m.id, m);
    if (msgs.size > KEEP) msgs.delete(msgs.keys().next().value);
    if (emitIt) emit({ t: 'msg', msg: m });
    save();
  }

  function setStatus(id, status, hops, via) {
    const m = msgs.get(id);
    if (!m) return;
    Object.assign(m, { status, hops, via });
    emit({ t: 'status', id, status, hops, via, rerouteVia: null });
    save();
  }

  function sec(kind, detail, src = null) {
    const event = { tsMs: now(), kind, detail, link: ws ? `web${conn}` : null, src };
    stats.drops[kind]++;
    security.push(event);
    if (security.length > 500) security.shift();
    emit({ t: 'sec', event });
  }

  // ---- what the UI reads ----

  function contactList() {
    return [...contacts].map(([id, c]) => {
      const fresh = authed && now() - c.heardAt < ROUTE_MS;
      const suspect = !verified.has(id) && [...verified].some((v) => v !== id && contacts.get(v) && M.normName(contacts.get(v).name) === M.normName(c.name));
      return { id, name: c.name, verified: verified.has(id), hops: fresh ? c.hops : null, via: fresh ? host : null, linked: authed && id === host,
        muted: muted.has(id), lastSeenMs: c.lastSeen, suspect, revoked: revoked.has(id) };
    });
  }
  const linkList = () => (ws ? [{ link: `web${conn}`, peer: authed ? host : null, transport: 'web', authed, tree: authed ? 'eager' : null,
    desc: 'web ' + url.replace(/pin=[^&]*/, 'pin=*') }] : []);
  const groupInfo = (g) => ({ id: g.id, name: g.name, owner: g.owner, members: [...g.members.keys()] });
  function statsNow() {
    const sorted = [...rtts].sort((a, b) => a - b);
    return { ...stats, ackRttMsLast: rtts.at(-1) ?? 0, ackRttMsMedian: sorted[sorted.length >> 1] ?? 0, drops: { ...stats.drops } };
  }

  function snapshot() {
    const all = [...msgs.values()].sort((a, b) => a.lamport - b.lamport || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
    const dms = {}, gdms = {};
    for (const m of all) {
      if (m.group) (gdms[m.group] ||= []).push(m);
      else if (m.to) (dms[m.mine ? m.to : m.from] ||= []).push(m);
    }
    return { t: 'snapshot', self: { id: me.idHex, name }, contacts: contactList(), links: linkList(),
      feed: all.filter((m) => m.to == null && m.group == null), dms, security: [...security], debug: { tamper: false, drop: false },
      groups: [...groups.values()].map(groupInfo), gdms, stats: statsNow(), profile: 'STANDARD',
      profileValues: { presenceMs: PRESENCE_MS, syncMs: SYNC_MS, jitterMinMs: 0, jitterMaxMs: 0, maxLinks: 1, relayStoreCap: 0, longRange: false } };
  }

  // ---- link: 5.5 handshake, reconnect with backoff ----

  function connect() {
    if (stopped) return;
    const s = (ws = new WS(url));
    s.binaryType = 'arraybuffer';
    conn++; authed = false; host = null; hostPub = null; myNonce = M.randomBytes(16); lastRx = now();
    wanted.clear(); outQ.length = 0;
    for (const o of outbox.values()) o.served = false;
    s.onopen = () => { if (ws === s) frame({ t: 'hello', pub: me.pub, nonce: myNonce }); };
    s.onmessage = (ev) => {
      if (ws !== s || typeof ev.data === 'string') return;   // text messages are ignored, as on the host
      try { onBytes(new Uint8Array(ev.data)); } catch (e) { console.error('webnode: frame failed', e); }
    };
    s.onerror = () => {};   // onclose follows
    s.onclose = () => {
      if (ws !== s) return;
      ws = null;
      if (authed) stats.linkDowns++;
      authed = false;
      emitNet();
      later(backoff, connect);
      backoff = Math.min(backoff * 2, 30_000);
    };
    emitNet();
  }

  function frame(f) {
    if (!ws || ws.readyState !== 1) return false;
    const b = M.encodeFrame(f);
    ws.send(b);
    stats.bytesOut += b.length;
    return true;
  }

  function onBytes(b) {
    lastRx = now();
    stats.bytesIn += b.length;
    const f = M.decodeFrame(b);
    if (!authed) {
      if (f?.t === 'hello') onHello(f); else if (f?.t === 'helloAuth') onHelloAuth(f);
      return;   // 5.5 step 4: nothing else before authentication
    }
    // T3: one handshake per link; a second HELLO/HELLO_AUTH is a re-binding attempt, as in core.
    if (f?.t === 'hello' || f?.t === 'helloAuth') { sec('LINK_CLOSED', 'handshake frame on a bound link', host); return ws.close(); }
    if (muted.has(host)) return;
    switch (f?.t) {
      case 'env': ingress(f.env); break;
      case 'syncOffer': case 'ihave': stats.syncRounds++; onOffer(f.ids); break;   // U9 rule 8: IHAVE like SYNC_OFFER
      case 'syncWant': case 'graft': onWant(f.ids); break;
      case undefined: stats.drops.MALFORMED++; break;
      default: break;   // BAN_PROOF, PRUNE: a leaf relays nothing, so it has nothing to do with them
    }
  }

  function onHello(f) {
    if (hostPub || !M.validPub(f.pub)) return;
    const id = M.nodeId(f.pub);
    if (hex(id) === me.idHex) return ws.close();
    hostPub = f.pub;
    frame({ t: 'helloAuth', sig: M.helloSign(me, f.nonce, id) });
  }

  function onHelloAuth(f) {
    if (!hostPub) return;
    if (!M.helloVerify(hostPub, myNonce, me.id, f.sig)) { sec('LINK_CLOSED', 'bad HELLO_AUTH'); return ws.close(); }
    authed = true; host = hex(M.nodeId(hostPub)); backoff = 1000; stats.linkUps++;
    // 5.5 step 6: presence, then whatever never reached a host, then an offer of the rest.
    sendPresence();
    for (const o of outbox.values()) if (!o.sent) give(o.env);
    offer();
    emitNet();
  }

  // ---- sending: own envelopes only, paced under the host's 30 per 10 s bucket ----

  function give(e) {
    if (!authed) return;
    outQ.push(e);
    drain();
  }
  function drain() {
    const t = now();
    tokens = Math.min(SEND_BUCKET, tokens + ((t - tokAt) * SEND_BUCKET) / BUCKET_MS); tokAt = t;
    while (outQ.length && tokens >= 1 && authed) { tokens--; sendEnv(outQ.shift()); }
    if (outQ.length && authed && !drainT) drainT = later(BUCKET_MS / SEND_BUCKET, () => { drainT = null; drain(); });
  }
  function sendEnv(e) {
    if (!frame({ t: 'env', env: e })) return;
    if (e.type === T.POST || e.type === T.PRESENCE) stats.fullSends++;
    const o = outbox.get(hex(M.msgId(e)));
    if (!o) return;
    o.sent = true;
    const shown = o.gm == null ? hex(M.msgId(e)) : o.gm;   // a group text shows under its group message id
    if (msgs.get(shown)?.status === 'QUEUED') setStatus(shown, 'SENT', 0, null);
  }

  function sendPresence() {
    const p = M.createEnvelope(me, T.PRESENCE, M.BROADCAST, ++lamport, epochNow(), 0, M.C.TTL_PRESENCE, M.presenceBody(name));
    remember(hex(M.msgId(p)), { type: T.PRESENCE, dst: null });
    give(p);
    save();
  }

  /** PoW off the caller's thread, one job at a time in order; null if the node wiped meanwhile. */
  function mine(type, dst, lam, ep, slot, ttl, body) {
    const ident = me;
    const job = powChain.then(async () => {
      const t0 = now();
      const core = M.encodeCore({ version: M.C.PROTOCOL_VERSION, type, srcPub: ident.pub, dst, lamport: BigInt(lam), epoch: ep, slot, initTtl: ttl, body });
      const nonce = await pow(core, M.powBits(body.length, ttl));
      stats.powMsLast = now() - t0;
      return M.createEnvelope(ident, type, dst, lam, ep, slot, ttl, body, { nonce });
    });
    powChain = job.catch(() => {});
    return job.then((e) => (ident === me && !stopped ? e : null));
  }

  /** [gm] null: a POST or DM shown on its own; '': a group invite; 'revoke': a U2 revoke; else the group message id it fans out. */
  function originate(e, gm, text, img) {
    if (!e) return;
    const mid = hex(M.msgId(e));
    stats.originated++;
    addOut(e, gm);
    if (gm == null) addMsg(msg({ id: mid, from: me.idHex, to: M.isBroadcast(e) ? null : hex(e.dst), text, lamport: Number(e.lamport), tsMs: now(), mine: true, status: 'QUEUED', img }));
    give(e);
    save();
  }

  function post(text) {
    const body = M.toUtf8(text);
    // 5.6 own limits: at most 3 per epoch, none in the start-up epoch (slots are not persisted, so a reload must not reuse one).
    let ep = Math.max(epochNow(), startEpoch + 1, postEpoch);
    if (ep === postEpoch && postSlots >= M.C.SLOTS_PER_EPOCH) ep++;
    if (ep !== postEpoch) { postEpoch = ep; postSlots = 0; }
    const slot = postSlots++, lam = ++lamport;
    later(Math.max(0, ep * EPOCH_MS - now()), () => mine(T.POST, M.BROADCAST, lam, ep, slot, M.C.TTL_POST, body).then((e) => originate(e, null, text)));
  }

  function dmTo(to, pub, plain, gm, text = '', img = null) {
    const body = M.sealDm(me, pub, plain);
    if (!body) return Promise.resolve();
    return mine(T.DM, unhex(to), ++lamport, epochNow(), 0, M.C.TTL_DM, body).then((e) => originate(e, gm, text, img));
  }

  // ---- 5.6 ingress ----

  function ingress(e) {
    const mid = hex(M.msgId(e));
    const asked = wanted.delete(mid);
    const s = seen.get(mid);
    if (s) {
      stats.duplicatesDropped++;
      if (s.ack && s.reAck !== conn) { s.reAck = conn; give(s.ack); }   // a resend of a DM I ACKed: the same ACK again, once per link
      return;
    }
    const src = hex(M.nodeId(e.srcPub));
    if ((bans.get(src) || 0) > now()) return;
    if (Number(e.lamport) > lamport + 1_000_000) { stats.drops.MALFORMED++; return; }
    const bad = M.checkEnvelope(e);
    if (bad === 'MALFORMED') { stats.drops.MALFORMED++; return; }
    if (bad) return sec(bad, `msg ${mid}`, src);
    const ep = epochNow();
    if (e.epoch < ep - M.C.MAX_AGE_EPOCHS || e.epoch > ep + M.C.MAX_FUTURE_EPOCHS) return sec('STALE', `epoch ${e.epoch}`, src);
    const dst = M.isBroadcast(e) ? null : hex(e.dst);
    // Verified from here on, so marking it seen cannot let a forged copy shadow the real one.
    if (!typeRules(e, src, mid)) { remember(mid, { type: e.type, dst }); return; }
    if (asked) stats.syncRecovered++;
    remember(mid, { type: e.type, dst });
    lamport = Math.max(lamport, Number(e.lamport));
    const hops = e.hops + 1;
    const c = contacts.get(src);
    if (c && (c.heardAt < now() - ROUTE_MS || hops <= c.hops)) { c.hops = hops; c.heardAt = now(); }
    const mine = src === me.idHex;
    if (e.type === T.PRESENCE) onPresence(e, src, hops);
    else if (e.type === T.POST) {
      if (!msgs.has(mid)) addMsg(msg({ id: mid, from: src, text: M.fromUtf8(e.body), lamport: Number(e.lamport), tsMs: now(), mine,
        status: mine ? 'SENT' : 'RECEIVED', hops: mine ? 0 : hops, via: mine ? null : host }));
    } else if (e.type === T.DM && dst === me.idHex) onDmToMe(e, mid, src, hops);
    else if (e.type === T.ACK && dst === me.idHex) onAck(e, hops);
    // Anything else (others' DMs and ACKs) stops here: a leaf never relays.
  }

  function typeRules(e, src, mid) {
    switch (e.type) {
      case T.POST: {
        if (e.slot >= M.C.SLOTS_PER_EPOCH) { sec('MALFORMED', `slot ${e.slot}`, src); return false; }
        const k = `${src}/${e.epoch}/${e.slot}`, first = ledger.get(k);
        if (first && first !== mid) {
          // shortcut: bans locally but sends no BAN_PROOF; the host and the mesh catch the same pair themselves.
          bans.set(src, now() + 600_000);
          sec('RATE_EQUIVOCATION', `two posts for epoch ${e.epoch} slot ${e.slot}; banned 10 min`, src);
          return false;
        }
        ledger.set(k, mid);
        return true;
      }
      case T.PRESENCE: {
        const last = lastPresence.get(src);
        if (last != null && now() - last < 10_000) return false;
        lastPresence.set(src, now());
        return true;
      }
      case T.ACK: { const s = seen.get(hex(e.body)); return !!s && s.type === T.DM && s.dst === src && !s.acked; }
      default: return true;
    }
  }

  function onPresence(e, src, hops) {
    if (src === me.idHex) return;
    const old = contacts.get(src);
    if (old && Number(e.lamport) <= old.lamport) return;
    // A full table refuses new unverified contacts, after dropping unverified ones silent for 10 presence intervals.
    if (!old && !verified.has(src) && contacts.size >= MAX_CONTACTS) {
      for (const [id, c] of contacts) if (!verified.has(id) && now() - c.lastSeen > 10 * PRESENCE_MS) contacts.delete(id);
      if (contacts.size >= MAX_CONTACTS) return;
    }
    contacts.set(src, { name: M.presenceName(e), pub: e.srcPub, lastSeen: now(), lamport: Number(e.lamport), hops, heardAt: now() });
    emitNet();
    save();
  }

  function onDmToMe(e, mid, src, hops) {
    if (revoked.has(src)) return;   // U2: the owner wiped this key
    const p = M.parsePlain(M.openDm(me, e, e.srcPub));
    if (!p) return;   // does not open, bad padding or unknown kind: not stored, not ACKed
    const base = { from: src, to: me.idHex, lamport: Number(e.lamport), tsMs: now(), mine: false, status: 'RECEIVED', hops, via: host };
    if (p.kind === 0 && !msgs.has(mid)) addMsg(msg({ ...base, id: mid, text: p.text }));
    else if (p.kind === 1 && !msgs.has(mid)) addMsg(msg({ ...base, id: mid, text: PHOTO, img: b64(p.jpeg) }));
    else if (p.kind === 2) onInvite(src, p);
    else if (p.kind === 3 || p.kind === 5) onGroupText(src, p, { ...base, to: null });
    else if (p.kind === 4) onRevoke(src);
    const a = M.createAck(me, e, ++lamport, epochNow());
    remember(hex(M.msgId(a)), { type: T.ACK, dst: src });
    Object.assign(seen.get(mid) || {}, { ack: a, reAck: conn });
    give(a);
  }

  function onAck(e, hops) {
    const dmId = hex(e.body);
    seen.get(dmId).acked = true;
    const o = outbox.get(dmId);
    outbox.delete(dmId);
    if (o?.gm?.length === 32) groupAck(o.gm);
    const m = msgs.get(dmId);
    if (m?.mine && m.status !== 'DELIVERED') {
      rtts.push(now() - m.tsMs); if (rtts.length > 64) rtts.shift();
      setStatus(dmId, 'DELIVERED', hops, host);
    }
    save();
  }

  // ---- 5.8 sync: want what the host offers and we lack; serve only our own envelopes ----

  function onOffer(ids) {
    const want = [];
    for (const b of ids.slice(0, MAX_WANT)) {
      const mid = hex(b), m = msgs.get(mid);
      // Known messages are skipped, except DMs to me: their sender still waits for the ACK this pull will repeat.
      if (seen.has(mid) || wanted.has(mid) || wanted.size >= MAX_WANT || (m && m.to !== me.idHex)) continue;
      wanted.add(mid); want.push(b);
    }
    if (want.length) frame({ t: 'syncWant', ids: want });
  }

  function onWant(ids) {
    for (const b of ids.slice(0, MAX_WANT)) {
      const o = outbox.get(hex(b));
      if (o && !o.served) { o.served = true; give(o.env); }
    }
  }

  function offer() {
    const ids = [...outbox.keys()].slice(-MAX_WANT).map(unhex);
    if (ids.length) frame({ t: 'syncOffer', ids });
  }

  function syncTick() {
    const ep = epochNow();
    for (const [mid, o] of outbox) if (o.env.type === T.POST && o.env.epoch < ep - M.C.MAX_AGE_EPOCHS) outbox.delete(mid);
    for (const [k, v] of ledger) if (Number(k.split('/')[1]) < ep - M.C.MAX_AGE_EPOCHS) ledger.delete(k);
    for (const [k, v] of lastPresence) if (now() - v >= 10_000) lastPresence.delete(k);
    for (const [k, v] of bans) if (v <= now()) bans.delete(k);
    if (!authed) return;
    if (now() - lastRx > SILENT_MS) { sec('LINK_CLOSED', 'silent for 45 s', host); return ws.close(); }
    offer();
  }

  // ---- 13.2 groups: fan-out DMs of kinds 2 (invite), 3 (text) and 5 (photo) ----

  const pubOf = (id) => contacts.get(id)?.pub || [...groups.values()].map((g) => g.members.get(id)).find(Boolean);
  /** Self first, then the known ids (unknown keys are skipped), 16 at most. */
  function memberKeys(ids) {
    const m = new Map([[me.idHex, me.pub]]);
    for (const i of ids) if (m.size < M.C.MAX_GROUP && !m.has(i)) { const p = pubOf(i); if (p) m.set(i, p); }
    return m;
  }
  function invite(g, to) {
    const plain = M.invitePlain(unhex(g.id), g.salt, g.name, [...g.members.values()]);
    for (const [m, pub] of to) if (m !== me.idHex) dmTo(m, pub, plain, '');
  }
  function setGroup(g) { groups.set(g.id, g); emit({ t: 'group', group: groupInfo(g) }); save(); }

  function createGroup(gname, ids) {
    const salt = M.randomBytes(8);
    const g = { id: hex(M.gidOf(me.id, salt)), salt, name: fitName(gname, 'group'), owner: me.idHex, members: memberKeys(ids) };
    setGroup(g);
    invite(g, g.members);
    return g.id;
  }

  function setGroupMembers(id, ids) {
    const old = groups.get(id);
    if (!old || old.owner !== me.idHex) throw new Error('not the owner of that group');
    const g = { ...old, members: memberKeys(ids) };
    setGroup(g);
    invite(g, new Map([...old.members, ...g.members]));   // a member left out gets the new list and drops the group
  }

  /** Kind 3 text, or kind 5 when [jpeg] is given ([text] is then PHOTO). */
  function sendGroup(gid, text, jpeg = null) {
    const g = groups.get(gid);
    if (!g) throw new Error('unknown group');
    const gm = M.randomBytes(8), uid = me.idHex + hex(gm);
    const others = [...g.members].filter(([m]) => m !== me.idHex && !revoked.has(m));
    addMsg(msg({ id: uid, from: me.idHex, text, lamport: ++lamport, tsMs: now(), mine: true, status: others.length ? 'QUEUED' : 'SENT',
      img: jpeg && b64(jpeg), group: gid, delivered: 0, total: others.length }));
    const plain = jpeg ? M.groupImagePlain(unhex(gid), gm, jpeg) : M.groupTextPlain(unhex(gid), gm, text);
    for (const [m, pub] of others) dmTo(m, pub, plain, uid);
  }

  function groupAck(uid) {
    const m = msgs.get(uid);
    if (!m) return;
    m.delivered = (m.delivered ?? 0) + 1;
    emit({ t: 'gstatus', id: uid, delivered: m.delivered, total: m.total ?? 0 });
  }

  function onInvite(src, p) {
    const gid = hex(p.gid);
    const members = new Map(p.members.map((k) => [hex(M.nodeId(k)), k]));   // ids derive from keys, so a key cannot be swapped
    const old = groups.get(gid);
    // Dropped silently: a gid not bound to src, a change not from the owner, or over the caps.
    if (gid !== hex(M.gidOf(unhex(src), p.salt)) || !members.has(src) || (old && old.owner !== src)) return;
    if (!old && members.has(me.idHex)) {
      if (!verified.has(src) && [...groups.values()].filter((g) => g.owner === src).length >= PER_OWNER) return;
      if (groups.size >= MAX_GROUPS) return;   // shortcut: a full table refuses new groups (Kotlin evicts the oldest unposted one)
    }
    const g = { id: gid, salt: p.salt, name: p.name, owner: src, members };
    if (members.has(me.idHex)) return setGroup(g);
    if (!old) return;
    groups.delete(gid);
    emit({ t: 'group', group: groupInfo(g) });   // members without me: the page drops it, history stays
    save();
  }

  function onGroupText(src, p, base) {
    const gid = hex(p.gid), uid = src + hex(p.gm);   // includes the sender, so one member cannot suppress another's message
    const g = groups.get(gid);
    if (g && g.members.has(src) && !msgs.has(uid)) addMsg(msg({ ...base, id: uid, text: p.jpeg ? PHOTO : p.text, img: p.jpeg ? b64(p.jpeg) : null, group: gid }));
  }

  /** U2: [x] wiped. Forget the DM conversation with x, x's group messages and x's groups; refuse x's key from now on. */
  function onRevoke(x) {
    const gone = new Set([...groups.values()].filter((g) => g.owner === x).map((g) => g.id));
    for (const [id, m] of msgs) if (m.group == null ? m.to != null && (m.from === x || m.to === x) : m.from === x || gone.has(m.group)) msgs.delete(id);
    gone.forEach((id) => groups.delete(id));
    revoked.add(x);
    for (const o of [...groups.values()]) if (o.owner === me.idHex && o.members.has(x)) {
      const g = { ...o, members: new Map(o.members) };
      g.members.delete(x);
      groups.set(g.id, g);
      invite(g, g.members);
    }
    sec('PEER_REVOKED', 'peer wiped its node', x);
    save();
    emit(snapshot());
  }

  /** U2: a revoke DM to every DM peer and group member, up to 3 s for them to go out, then a new identity and empty state. */
  async function panic() {
    const peers = new Map();
    for (const m of msgs.values()) if (m.group == null && m.to != null) { const p = m.mine ? m.to : m.from; const k = pubOf(p); if (k) peers.set(p, k); }
    for (const g of groups.values()) for (const [i, k] of g.members) peers.set(i, k);
    peers.delete(me.idHex);
    const deadline = now() + REVOKE_WAIT_MS;
    await Promise.race([Promise.all([...peers].map(([p, k]) => dmTo(p, k, M.revokePlain(), 'revoke'))), sleep(REVOKE_WAIT_MS)]);
    while (now() < deadline && [...outbox.values()].some((o) => o.gm === 'revoke' && !o.sent)) await sleep(50);
    await sleep(300);   // let the socket write them before it closes
    gen++; drainT = null;   // the old identity's timers no longer fire
    reset(M.createIdentity());
    save(true);
    if (ws) ws.close(); else connect();   // the host re-handshakes with the new key
    emit(snapshot());
  }

  // ---- UI commands (Cmd.kt parseCmd rules) ----

  function run(c) {
    if (!c || typeof c !== 'object' || Array.isArray(c)) throw new Error('not a JSON object');
    const str = (k) => { if (typeof c[k] !== 'string') throw new Error(`missing string field '${k}'`); return c[k]; };
    const bool = (k) => { if (typeof c[k] !== 'boolean') throw new Error(`missing boolean field '${k}'`); return c[k]; };
    const id = (k) => { const v = str(k); if (!NODE_ID.test(v)) throw new Error(`'${k}' must be 16 lowercase hex chars`); return v; };
    const text = (k, max) => {
      const v = str(k);
      if (!v.trim()) throw new Error(`'${k}' is empty`);
      if (M.toUtf8(v).length > max) throw new Error(`'${k}' longer than ${max} bytes`);
      return v;
    };
    const jpeg = (s, max) => {
      if (s.length > 53_400) throw new Error("'img' too long");
      let b; try { b = unb64(s); } catch { throw new Error("'img' is not base64"); }
      if (b.length > max) throw new Error(`'img' larger than ${max.toLocaleString('en-US')} bytes`);
      if (!M.isJpeg(b)) throw new Error("'img' is not a JPEG");
      return b;
    };
    const members = () => {
      if (!Array.isArray(c.members)) throw new Error("missing array field 'members'");
      const m = [...new Set(c.members.map((x) => { if (typeof x !== 'string' || !NODE_ID.test(x)) throw new Error("'members' must be 16-hex node ids"); return x; }))];
      if (m.length > M.C.MAX_GROUP) throw new Error('at most 16 members');
      return m;
    };
    const t = str('t');
    switch (t) {
      case 'post': return post(text('text', M.C.MAX_POST_BODY));
      case 'dm': {
        const to = id('to');
        let plain, txt = PHOTO, img = null;
        if ('img' in c) {
          const s = str('img'), b = jpeg(s, 39_999);
          plain = M.concat(Uint8Array.of(M.DmKind.PHOTO), b); img = s;
        } else { txt = text('text', 39_999); plain = M.textPlain(txt); }
        const pub = contacts.get(to)?.pub;
        if (!pub) throw new Error('unknown contact');
        return void dmTo(to, pub, plain, null, txt, img);
      }
      case 'name': name = fitName(text('name', M.C.MAX_NAME), name); save(); return sendPresence();
      case 'mute': (bool('on') ? muted.add(id('id')) : muted.delete(id('id'))); save(); return emitNet();
      case 'verify': (bool('on') ? verified.add(id('id')) : verified.delete(id('id'))); save(); return emitNet();
      case 'group': return void createGroup(text('name', M.C.MAX_NAME), members());
      case 'group-members': return setGroupMembers(id('id'), members());
      case 'gmsg': return 'img' in c ? sendGroup(id('group'), PHOTO, jpeg(str('img'), M.MAX_GROUP_JPEG)) : sendGroup(id('group'), text('text', M.C.MAX_POST_BODY));
      case 'panic': if (str('confirm') !== 'WIPE') throw new Error("'confirm' must be WIPE"); return void panic();
      case 'debug': case 'attack': case 'profile': throw new Error(`'${t}' is not available on a browser node`);
      default: throw new Error(`unknown t '${t}'`);
    }
  }

  function cmd(c) {
    try { run(c); return { ok: true }; } catch (e) {
      emit({ t: 'error', cmd: c?.t ?? null, error: e.message });
      return { ok: false, error: e.message };
    }
  }

  // ---- start ----

  load();
  const loops = [setInterval(() => { if (!stopped) { sendPresence(); } }, PRESENCE_MS), setInterval(syncTick, SYNC_MS),
    setInterval(() => emit({ t: 'stats', stats: statsNow() }), 2_000)];
  connect();

  return {
    get id() { return me.idHex; },
    get connected() { return authed; },
    snapshot: () => JSON.parse(JSON.stringify(snapshot())),
    onEvent(fn) { listeners.add(fn); try { fn(JSON.parse(JSON.stringify(snapshot()))); } catch (e) { console.error(e); } return () => listeners.delete(fn); },
    cmd,
    stop() {
      stopped = true;
      loops.forEach(clearInterval); timers.forEach(clearTimeout); timers.clear();
      flush();
      if (ws) { const s = ws; ws = null; s.close(); }
    },
  };
}
