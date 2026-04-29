const SITE_VERSION = '2026-04-28-v2';
const CACHE_NAME = `coinbase-magician-${SITE_VERSION}`;

self.addEventListener('install', event => {
  self.skipWaiting();
});

self.addEventListener('activate', event => {
  event.waitUntil((async () => {
    const keys = await caches.keys();
    await Promise.all(keys.map(key => caches.delete(key)));
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', event => {
  const request = event.request;
  if (request.method !== 'GET') return;

  const url = new URL(request.url);

  if (url.hostname.includes('api.exchange.coinbase.com')) {
    event.respondWith(fetch(request, { cache: 'no-store' }));
    return;
  }

  if (request.mode === 'navigate' || ['document', 'script', 'style'].includes(request.destination)) {
    event.respondWith(fetch(request, { cache: 'reload' }).catch(() => caches.match(request)));
    return;
  }

  event.respondWith(fetch(request, { cache: 'no-store' }).catch(() => caches.match(request)));
});
