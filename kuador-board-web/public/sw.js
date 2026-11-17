/*
 * Service worker de Kuador: hace instalable la app y muestra las notificaciones push. No guarda nada en caché: el
 * tablero siempre muestra datos frescos (y con sesión), así que no hay modo sin conexión.
 */
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));

self.addEventListener("push", (event) => {
  let data = {};
  try {
    data = event.data ? event.data.json() : {};
  } catch {
    data = { title: "Kuador", body: event.data ? event.data.text() : "" };
  }
  const title = data.title || "Kuador";
  event.waitUntil(
    self.registration.showNotification(title, {
      body: data.body || "",
      icon: "/icons/icon-192.png",
      badge: "/icons/icon-192.png",
      data: { url: data.url || "/decisions" },
      tag: data.url || undefined,
    }),
  );
});

// Tocar la notificación abre (o enfoca) la app en la tarjeta del aviso. Solo rutas del mismo origen.
self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  let target = "/decisions";
  try {
    const url = new URL(event.notification.data && event.notification.data.url ? event.notification.data.url : "/decisions", self.location.origin);
    target = url.origin === self.location.origin ? url.pathname + url.search : "/decisions";
  } catch {
    target = "/decisions";
  }
  event.waitUntil(
    self.clients.matchAll({ type: "window", includeUncontrolled: true }).then((wins) => {
      for (const w of wins) {
        if ("focus" in w) {
          w.navigate(target);
          return w.focus();
        }
      }
      return self.clients.openWindow(target);
    }),
  );
});
