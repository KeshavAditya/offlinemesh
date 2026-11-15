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
  const Counted = class extends WebSocket {
    constructor(u) {
      super(u);
      const g = gen;
      this.addEventListener('close', () => { if (g !== gen) return; if (node?.connected) fails = 0; else if (++fails >= 3) ask(); });
    }
  };

  function start() {
    const g = ++gen; fails = 0;
    import('/meshnode.js').then(({ createWebNode }) => {
      if (g !== gen) return;
      const url = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/mesh?pin=' + encodeURIComponent(pin);
      node = createWebNode({ url, storage, powMine, WebSocket: Counted });
      node.onEvent((ev) => { if (ev.t === 'net') up(node.connected); if (ev.t !== 'error') evs.forEach((f) => f(ev)); });
    }, (e) => { console.error('webbackend: meshnode.js failed to load', e); up(false); });
  }

  // "Enter the new PIN" prompt, built from the page's own classes.
  const form = document.createElement('form');
  form.className = 'box'; form.hidden = true;
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

  const qr = (text) => '/api/qr?text=' + encodeURIComponent(text);

  // App download (GuestServer puts OM_APK = { sha256 } in the page when it has the APK).
  if (window.OM_APK) {
    const d = document.createElement('details');
    d.innerHTML = '<summary>Get the Android app</summary><p><a href="/app.apk" download="OfflineMesh.apk">Download OfflineMesh.apk</a></p>'
      + '<p class="mut">File SHA-256, check it before installing:<br><span class="mono"></span></p><img class="qr" alt="QR code of the app download link">';
    d.querySelector('.mono').textContent = OM_APK.sha256.match(/.{4}/g).join(' ');
    d.querySelector('img').src = qr(new URL('/app.apk', location.href).href);
    $('posts').before(d);
  }

  const b = window.OM_BACKEND = {
    powVia: null,
    onEvent: (f) => evs.push(f),
    onState: (f) => sts.push(f),
    connect: () => (pin ? start() : ask()),
    send(cmd) {
      if (!node) return Promise.reject(new Error('not joined yet'));
      const r = node.cmd(cmd);
      return r.ok ? Promise.resolve() : Promise.reject(new Error(r.error));
    },
    qr,
    guest: () => Promise.resolve(null),
  };
})();
