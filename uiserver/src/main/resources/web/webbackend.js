// Browser backend for index.html when it is served by GuestServer (5.13): this page itself is a leaf mesh node
// (meshnode.js) linked to the host over ws /mesh?pin=. Sets window.OM_BACKEND before index.html's script runs.
// Classic script on purpose (index.html reads OM_BACKEND synchronously); meshnode.js is loaded with import() on connect().
(() => {
  const $ = (id) => document.getElementById(id);
  const evs = [], sts = [];
  let node = null, gen = 0, fails = 0, pin = new URLSearchParams(location.search).get('pin') || '';

  // localStorage when the browser allows it (private mode may throw), else memory: a new identity on every reload.
  const mem = new Map();
  const storage = {
    get: (k) => { try { return localStorage.getItem(k); } catch { return mem.get(k) ?? null; } },
    set: (k, v) => { try { localStorage.setItem(k, v); } catch { mem.set(k, v); } },
  };

  // PoW in a module Worker (no crypto.subtle needed: meshcore.js hashes with noble). Inline if Workers fail.
  const coreUrl = new URL('/meshcore.js', location.href).href;
  const inline = (core, bits) => import(coreUrl).then((M) => M.powMine(core, bits));
  const jobs = new Map();
  let worker = null, seq = 0;
  try {
    const src = `import { powMine } from '${coreUrl}';\nonmessage = (e) => postMessage({ id: e.data.id, n: String(powMine(e.data.core, e.data.bits)) });`;
    worker = new Worker(URL.createObjectURL(new Blob([src], { type: 'text/javascript' })), { type: 'module' });
    worker.onmessage = (e) => { const j = jobs.get(e.data.id); jobs.delete(e.data.id); b.powVia = 'worker'; j?.resolve(BigInt(e.data.n)); };
    worker.onerror = (e) => {
      e.preventDefault(); console.warn('webbackend: PoW worker failed, mining inline', e.message);
      worker.terminate(); worker = null;
      for (const j of jobs.values()) inline(j.core, j.bits).then(j.resolve, j.reject);
      jobs.clear();
    };
  } catch (e) { worker = null; }
  const powMine = (core, bits) => worker
    ? new Promise((resolve, reject) => { const id = ++seq; jobs.set(id, { core, bits, resolve, reject }); worker.postMessage({ id, core, bits }); })
    : (b.powVia = 'inline', inline(core, bits));

  const up = (u) => sts.forEach((f) => f(u));

  // Counts link attempts that close before the host authenticated us: a wrong or rotated PIN gets a 403 the browser
  // cannot see, so after 3 in a row stop (each try costs one of the host's PIN attempts) and ask for the PIN.
  // Sockets the node closes itself (stuck or stale after sleep) and failures while offline do not count.
  const Counted = class extends WebSocket {
    constructor(u) {
      super(u);
      const g = gen;
      this.addEventListener('close', () => { if (g !== gen || this.mine || !navigator.onLine) return; if (node?.connected) fails = 0; else if (++fails >= 3) ask(); });
    }
    close(...a) { this.mine = true; super.close(...a); }
  };

  function start() {
    const g = ++gen; fails = 0;
    import('/meshnode.js').then(({ createWebNode }) => {
      if (g !== gen) return;
      const url = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/mesh?pin=' + encodeURIComponent(pin);
      node = createWebNode({ url, storage, powMine, WebSocket: Counted });
      node.onEvent((ev) => { if (ev.t === 'net') up(node.connected); if (ev.t === 'msg') alert(ev.msg); if (ev.t !== 'error') evs.forEach((f) => f(ev)); });
    }, (e) => { console.error('webbackend: meshnode.js failed to load', e); up(false); });
  }

  // "Enter the new PIN" prompt, built from the page's own classes.
  const form = document.createElement('form');
  form.className = 'box'; form.hidden = true; form.style.marginTop = '8px';
  form.innerHTML = '<p>Can\'t join the host. If its PIN changed, enter the new PIN.</p>'
    + '<div class="row" style="margin-bottom:12px"><input type="text" id="pinIn" inputmode="numeric" maxlength="4" autocomplete="off" aria-label="PIN">'
    + '<button class="pri">Join</button></div>';
  form.onsubmit = (e) => {
    e.preventDefault();
    pin = $('pinIn').value.trim();
    if (!pin) return;
    history.replaceState(null, '', '?pin=' + encodeURIComponent(pin));
    form.hidden = true; start();
  };
  document.querySelector('main').prepend(form);
  function ask() {
    gen++; if (node) { node.stop(); node = null; }
    up(false);
    form.hidden = false; $('pinIn').value = pin; $('pinIn').focus();
  }

  // A leaf has no relay to tamper with and no profile to pick; meshnode.js refuses those commands anyway.
  const prof = $('profile');
  for (const x of [prof.previousElementSibling, prof, prof.nextElementSibling, $('tamper').closest('fieldset')]) x.hidden = true;

  // Back from sleep or a hidden tab: reconnect at once instead of waiting out the backoff (ADVANCES_2 #2).
  // The 1 s tick notices a frozen page (timers stopped for over 5 s) on browsers that fire no event.
  const wake = () => { if (!document.hidden) node?.wake(); };
  for (const e of ['pageshow', 'online', 'focus']) addEventListener(e, wake);
  for (const e of ['visibilitychange', 'resume']) document.addEventListener(e, wake);
  let tick = Date.now();
  setInterval(() => { if (Date.now() - tick > 5_000) wake(); tick = Date.now(); }, 1_000);

  // Plain HTTP has no Notifications, so a DM or group message to a hidden tab shows in the title and vibrates (Android).
  const title = document.title;
  let unread = 0;
  function alert(m) {
    if (!document.hidden || m.mine || !(m.to || m.group)) return;
    document.title = '(' + ++unread + ') ' + title;
    try { navigator.vibrate?.(200); } catch {}
  }
  document.addEventListener('visibilitychange', () => { if (!document.hidden) { unread = 0; document.title = title; } });
  const keep = document.createElement('p');
  keep.className = 'mut'; keep.textContent = 'Keep this tab open to receive messages.';
  document.querySelector('main').prepend(keep);

  // One tab per stored identity (ADVANCES_2 #13): two tabs on one key would send with the same counters and look like an
  // equivocator. A new tab asks on a BroadcastChannel; a running tab answers 'taken'. "Use here" makes the old tab stop
  // (it saves first) and answer 'free'. BroadcastChannel needs no secure context; Web Locks does.
  // shortcut: two tabs opened within 300 ms can both start; fine for a guest opening one link.
  let bc = null;
  try { bc = new BroadcastChannel('offlinemesh'); } catch {}
  const busy = document.createElement('div');
  busy.className = 'box'; busy.hidden = true;
  busy.innerHTML = '<p>OfflineMesh is open in another tab.</p><p><button class="pri">Use here</button></p>';
  document.querySelector('main').prepend(busy);
  const ask1 = (t, ms) => new Promise((res) => {
    if (!bc) return res(null);
    const on = (e) => { if (e.data === 'taken' || e.data === 'free') { bc.removeEventListener('message', on); res(e.data); } };
    bc.addEventListener('message', on);
    bc.postMessage(t);
    setTimeout(() => { bc.removeEventListener('message', on); res(null); }, ms);
  });
  if (bc) bc.onmessage = (e) => {
    if (!node) return;
    if (e.data === 'here') bc.postMessage('taken');
    if (e.data === 'take') { gen++; node.stop(); node = null; up(false); busy.hidden = false; bc.postMessage('free'); }
  };
  const run = () => { busy.hidden = true; pin ? start() : ask(); };
  busy.querySelector('button').onclick = () => ask1('take', 1_000).then(run);

  const qr = (text) => '/api/qr?text=' + encodeURIComponent(text);

  // App download (GuestServer puts OM_APK = { sha256 } in the page when it has the APK).
  if (window.OM_APK) {
    const d = document.createElement('details');
    d.innerHTML = '<summary>Get the Android app</summary><p><a href="/app.apk" download="OfflineMesh.apk">Download OfflineMesh.apk</a></p>'
      + '<p class="mut">File SHA-256, check it before installing:<br><span class="mono"></span></p><img class="qr" alt="QR code of the app download link">';
    d.querySelector('.mono').textContent = OM_APK.sha256.match(/.{4}/g).join(' ');
    d.querySelector('img').src = qr(new URL('/app.apk', location.href).href);
    $('guest').before(d);   // Me tab, under my fingerprint
  }

  const b = window.OM_BACKEND = {
    powVia: null,
    onEvent: (f) => evs.push(f),
    onState: (f) => sts.push(f),
    connect: () => ask1('here', 300).then((r) => (r === 'taken' ? (busy.hidden = false, up(false)) : run())),
    send(cmd) {
      if (!node) return Promise.reject(new Error('not joined yet'));
      const r = node.cmd(cmd);
      return r.ok ? Promise.resolve() : Promise.reject(new Error(r.error));
    },
    qr,
    guest: () => Promise.resolve(null),
  };
})();
