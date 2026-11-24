// App del equipo: primero la red (siempre la versión más nueva); sin internet, abre la última guardada.
// Solo guarda la página y sus íconos, nunca datos de pacientes (esos van directo a Supabase).
const C = 'mct-equipo-v1';
const SHELL = ['./', './manifest.webmanifest', './icon-192.png', './icon-512.png'];
self.addEventListener('install', (e) => {
  self.skipWaiting();
  e.waitUntil(caches.open(C).then((c) => c.addAll(SHELL)));
});
self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((ks) => Promise.all(ks.filter((k) => k !== C).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', (e) => {
  const r = e.request;
  const u = new URL(r.url);
  if (r.method !== 'GET' || u.origin !== location.origin || !u.pathname.startsWith(new URL('./', self.registration.scope).pathname)) return;
  e.respondWith(
    fetch(r)
      .then((res) => {
        if (res.ok) { const cp = res.clone(); caches.open(C).then((c) => c.put(r.mode === 'navigate' ? './' : r, cp)); }
        return res;
      })
      .catch(() => caches.match(r.mode === 'navigate' ? './' : r, { ignoreSearch: true }).then((m) => m || caches.match('./')))
  );
});
