// meshnode.js against real desktop nodes. One command, from the repo root (Node 22, java on PATH):
//   node uiserver/src/test/js/webnode-interop.mjs
// Builds :desktop:installDist (SKIP_BUILD=1 skips), starts desktop H (with --guest-port) and F wired H<->F on free ports
// with temp data dirs, joins H as a browser leaf node over ws /mesh, checks posts, 2-hop DMs and groups both ways,
// then kills both nodes. Exit code 0 only if every check passed.
import { spawn, spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createWebNode } from '../../main/resources/web/meshnode.js';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../..');
const lib = path.join(root, 'desktop/build/install/desktop/lib');
const tmp = mkdtempSync(path.join(tmpdir(), 'webnode-interop-'));
const procs = [], nodes = [], apis = [];
let web = null, failed = 0;

const freePort = () => new Promise((res, rej) => {
  const s = createServer().once('error', rej).listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => res(p)); });
});
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function until(what, fn, ms = 45_000) {
  const end = Date.now() + ms;
  for (;;) {
    try { const v = await fn(); if (v) return v; } catch { /* not up yet */ }
    if (Date.now() > end) throw new Error('timed out: ' + what);
    await sleep(300);
  }
}
async function check(name, fn) {
  const t0 = Date.now();
  try { await fn(); console.log(`PASS ${name} (${Date.now() - t0} ms)`); } catch (e) {
    failed++; console.log(`FAIL ${name}: ${e.message}`);
    if (process.env.DEBUG) console.log(JSON.stringify({ jsStats: web?.snapshot().stats, jsGroups: web?.snapshot().groups, hf: await Promise.all(apis.map(async (a) => { const s = await a.snap(); return { sec: s.security.slice(-10), drops: s.stats.drops, st: s.stats, groups: s.groups, gdms: s.gdms }; })) }, null, 1).slice(0, 20000));
  }
}

function desktop(n, args) {
  const out = [];
  const p = spawn('java', ['-cp', path.join(lib, '*'), 'mesh.desktop.MainKt', '--data', path.join(tmp, n), '--no-discovery', ...args.map(String)],
    { stdio: ['ignore', 'pipe', 'pipe'] });
  p.stdout.on('data', (d) => out.push(String(d)));
  p.stderr.on('data', (d) => out.push(String(d)));
  procs.push(p);
  const node = { name: n, out: () => out.join('') };
  nodes.push(node);
  return node;
}
const api = (ui) => ({
  snap: async () => (await fetch(`http://127.0.0.1:${ui}/api/snapshot`)).json(),
  cmd: async (c) => { const r = await fetch(`http://127.0.0.1:${ui}/api/cmd`, { method: 'POST', headers: { 'X-OM': '1' }, body: JSON.stringify(c) }); if (!r.ok) throw new Error(await r.text()); },
});

function cleanup() {
  try { web?.stop(); } catch { /* already stopped */ }
  for (const p of procs) try { p.kill(); } catch { /* gone */ }
}
process.on('SIGINT', () => { cleanup(); process.exit(130); });

try {
  if (!process.env.SKIP_BUILD) {
    const gradlew = process.platform === 'win32' ? '.\\gradlew.bat' : './gradlew'; // explicit .\ : cmd may not search cwd
    const b = spawnSync(gradlew, [':desktop:installDist', '-q'], { cwd: root, encoding: 'utf8', shell: process.platform === 'win32' });
    if (b.status !== 0) throw new Error('build failed:\n' + (b.stderr || b.stdout).split('\n').slice(-20).join('\n'));
  }
  const [hUi, hLan, hGuest, fUi, fLan] = [await freePort(), await freePort(), await freePort(), await freePort(), await freePort()];
  const H = desktop('H', ['--ui-port', hUi, '--listen', hLan, '--guest-port', hGuest]);
  desktop('F', ['--ui-port', fUi, '--listen', fLan, '--peer', `127.0.0.1:${hLan}`]);
  const h = api(hUi), f = api(fUi);
  apis.push(h, f);
  const pin = await until('H prints its guest PIN', () => H.out().match(/mesh\?pin=(\w+)/)?.[1]);
  const hId = (await until('H up', h.snap)).self.id, fId = (await until('F up', f.snap)).self.id;
  await until('H and F linked', async () => (await f.snap()).contacts.some((c) => c.id === hId && c.linked));

  // The JS node, with an in-memory storage like localStorage.
  const store = new Map();
  const events = [];
  web = createWebNode({ url: `ws://127.0.0.1:${hGuest}/mesh?pin=${pin}`, storage: store });
  web.onEvent((ev) => events.push(ev));
  const jsId = web.id;
  const seenEv = (pred) => events.find(pred);
  console.log(`H ${hId}  F ${fId}  JS ${jsId}  guest :${hGuest} (data ${tmp})`);

  await check('handshake: JS authed with H; F and JS see each other', async () => {
    await until('JS connected', () => web.connected);
    await until('F sees JS', async () => (await f.snap()).contacts.some((c) => c.id === jsId));
    await until('JS sees F', () => web.snapshot().contacts.some((c) => c.id === fId && c.hops === 2));
  });

  await check('post JS -> F', async () => {
    web.cmd({ t: 'post', text: 'hello from js' });
    await until('F feed has it', async () => (await f.snap()).feed.some((m) => m.text === 'hello from js' && m.from === jsId && m.hops === 2));
  });

  await check('post F -> JS', async () => {
    await f.cmd({ t: 'post', text: 'hello from F' });
    await until('JS msg event', () => seenEv((e) => e.t === 'msg' && e.msg.text === 'hello from F' && e.msg.from === fId && e.msg.status === 'RECEIVED'));
  });

  await check('DM JS -> F (2 hops) DELIVERED', async () => {
    web.cmd({ t: 'dm', to: fId, text: 'dm from js' });
    const sent = await until('JS own DM', () => seenEv((e) => e.t === 'msg' && e.msg.text === 'dm from js' && e.msg.mine));
    await until('F has it', async () => ((await f.snap()).dms[jsId] || []).some((m) => m.text === 'dm from js' && m.status === 'RECEIVED'));
    await until('JS DELIVERED, 2 hops', () => seenEv((e) => e.t === 'status' && e.id === sent.msg.id && e.status === 'DELIVERED' && e.hops === 2));
  });

  await check('DM F -> JS RECEIVED, DELIVERED on F', async () => {
    await f.cmd({ t: 'dm', to: jsId, text: 'dm from F' });
    await until('JS RECEIVED', () => seenEv((e) => e.t === 'msg' && e.msg.text === 'dm from F' && e.msg.status === 'RECEIVED' && e.msg.hops === 2));
    await until('F DELIVERED', async () => ((await f.snap()).dms[jsId] || []).some((m) => m.text === 'dm from F' && m.status === 'DELIVERED'));
  });

  await check('group owned by JS with H and F: text both ways', async () => {
    web.cmd({ t: 'group', name: 'jsgroup', members: [hId, fId] });
    const gid = (await until('JS group event', () => seenEv((e) => e.t === 'group' && e.group.name === 'jsgroup'))).group.id;
    for (const n of [h, f]) await until('invite reached', async () => (await n.snap()).groups.some((g) => g.id === gid && g.members.length === 3));
    web.cmd({ t: 'gmsg', group: gid, text: 'group hi from js' });
    for (const n of [h, f]) await until('gmsg reached', async () => ((await n.snap()).gdms[gid] || []).some((m) => m.text === 'group hi from js'));
    const own = seenEv((e) => e.t === 'msg' && e.msg.text === 'group hi from js').msg;
    await until('JS gstatus 2/2', () => seenEv((e) => e.t === 'gstatus' && e.id === own.id && e.delivered === 2 && e.total === 2));
    await f.cmd({ t: 'gmsg', group: gid, text: 'group hi from F' });
    await until('JS got F group text', () => seenEv((e) => e.t === 'msg' && e.msg.group === gid && e.msg.text === 'group hi from F' && e.msg.from === fId));
  });

  await check('group owned by H with JS and F: invite and text reach JS, JS reply reaches F', async () => {
    await h.cmd({ t: 'group', name: 'hgroup', members: [jsId, fId] });
    const gid = (await until('JS got invite', () => seenEv((e) => e.t === 'group' && e.group.name === 'hgroup' && e.group.owner === hId))).group.id;
    await h.cmd({ t: 'gmsg', group: gid, text: 'hgroup hi' });
    await until('JS got H group text', () => seenEv((e) => e.t === 'msg' && e.msg.group === gid && e.msg.text === 'hgroup hi'));
    web.cmd({ t: 'gmsg', group: gid, text: 'hgroup reply from js' });
    await until('F got JS reply', async () => ((await f.snap()).gdms[gid] || []).some((m) => m.text === 'hgroup reply from js' && m.from === jsId));
  });

  await check('refused commands give an error event', async () => {
    const r = web.cmd({ t: 'attack', kind: 'flood' });
    if (r.ok || !seenEv((e) => e.t === 'error' && e.cmd === 'attack')) throw new Error('attack was not refused');
    if (web.cmd({ t: 'post', text: '' }).ok) throw new Error('empty post accepted');
  });

  await check('reload: same identity and messages from storage, reconnects', async () => {
    const n = web.snapshot().feed.length + Object.values(web.snapshot().dms).flat().length;
    web.stop();
    web = createWebNode({ url: `ws://127.0.0.1:${hGuest}/mesh?pin=${pin}`, storage: store });
    const s = web.snapshot();
    if (s.self.id !== jsId) throw new Error('new identity after reload');
    if (s.feed.length + Object.values(s.dms).flat().length !== n) throw new Error('messages lost');
    await until('reconnected', () => web.connected);
  });

  await check('offline DM is kept in storage and delivered after reconnect', async () => {
    web.stop();
    const dead = await freePort();
    web = createWebNode({ url: `ws://127.0.0.1:${dead}/mesh`, storage: store });
    web.cmd({ t: 'dm', to: fId, text: 'queued while offline' });
    await until('queued', () => web.snapshot().dms[fId]?.some((m) => m.text === 'queued while offline' && m.status === 'QUEUED'));
    web.stop();
    await sleep(500);   // past the debounced save
    web = createWebNode({ url: `ws://127.0.0.1:${hGuest}/mesh?pin=${pin}`, storage: store });
    await until('F got it', async () => ((await f.snap()).dms[jsId] || []).some((m) => m.text === 'queued while offline'));
    await until('DELIVERED', () => web.snapshot().dms[fId]?.some((m) => m.text === 'queued while offline' && m.status === 'DELIVERED'));
  });

  await check('panic: F logs PEER_REVOKED, JS comes back with a new identity', async () => {
    web.cmd({ t: 'panic', confirm: 'WIPE' });
    await until('F revoked JS', async () => (await f.snap()).security.some((x) => x.kind === 'PEER_REVOKED' && x.src === jsId));
    await until('new id', () => web.id !== jsId && web.connected);
    if (Object.keys(web.snapshot().dms).length || web.snapshot().groups.length) throw new Error('DMs or groups survived the wipe');   // public posts sync back, as on any new node
  });
} catch (e) {
  failed++;
  console.log('FAIL setup: ' + e.message);
  for (const n of nodes) console.log(`--- ${n.name} output (tail)
` + n.out().slice(-2000));
} finally {
  cleanup();
  await sleep(500);
  try { rmSync(tmp, { recursive: true, force: true }); } catch { /* java may still hold files on Windows */ }
}
console.log(failed ? `${failed} check(s) FAILED` : 'ALL PASS');
process.exit(failed ? 1 : 0);
