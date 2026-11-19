"use client";

import { useEffect, useState, useTransition } from "react";

import { subscribePushAction, unsubscribePushAction } from "@/app/actions";
import { Switch } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";
import type { NotificationView, PushSubscriptionInput } from "@/lib/types";

type State = "loading" | "unsupported" | "ios-install" | "denied" | "off" | "on";

/** La clave VAPID (base64url) como la pide PushManager.subscribe. */
function keyBytes(base64url: string): Uint8Array<ArrayBuffer> {
  const b64 = (base64url + "=".repeat((4 - (base64url.length % 4)) % 4)).replace(/-/g, "+").replace(/_/g, "/");
  const raw = atob(b64);
  const out = new Uint8Array(new ArrayBuffer(raw.length));
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
  return out;
}

function detect(): State {
  if (!("serviceWorker" in navigator) || !("PushManager" in window) || !("Notification" in window)) {
    // En iPhone las notificaciones push solo existen con la app agregada a la pantalla de inicio.
    const ios = /iPhone|iPad|iPod/.test(navigator.userAgent);
    return ios ? "ios-install" : "unsupported";
  }
  return Notification.permission === "denied" ? "denied" : "off";
}

/** Notificaciones push en este dispositivo (la app instalada o este navegador): activarlas es suscribir el dispositivo. */
export function PushDeviceRow({ view, onChange }: { view: NotificationView; onChange: (v: NotificationView) => void }) {
  const [state, setState] = useState<State>("loading");
  const [pending, start] = useTransition();

  useEffect(() => {
    const s = detect();
    if (s !== "off") {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- se sabe recién en el navegador
      setState(s);
      return;
    }
    navigator.serviceWorker.ready
      .then((reg) => reg.pushManager.getSubscription())
      .then((sub) => setState(sub ? "on" : "off"))
      .catch(() => setState("off"));
  }, []);

  const enable = () =>
    start(async () => {
      if (!view.pushPublicKey) return;
      const permission = await Notification.requestPermission();
      if (permission !== "granted") {
        setState(permission === "denied" ? "denied" : "off");
        return;
      }
      try {
        const reg = await navigator.serviceWorker.ready;
        const sub = (await reg.pushManager.getSubscription()) ?? (await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: keyBytes(view.pushPublicKey) }));
        const r = await subscribePushAction(sub.toJSON() as PushSubscriptionInput);
        if (!r.ok) {
          notify.error("No se pudieron activar", r.error);
          return;
        }
        onChange(r.data);
        setState("on");
        notify.ok("Notificaciones activadas en este dispositivo");
      } catch {
        notify.error("No se pudieron activar", "El navegador no permitió suscribirse a las notificaciones.");
      }
    });

  const disable = () =>
    start(async () => {
      const reg = await navigator.serviceWorker.ready;
      const sub = await reg.pushManager.getSubscription();
      if (sub) {
        const endpoint = sub.endpoint;
        await sub.unsubscribe().catch(() => {});
        const r = await unsubscribePushAction(endpoint);
        if (r.ok) onChange(r.data);
      }
      setState("off");
    });

  const hint = !view.pushPublicKey
    ? "El servidor todavía no tiene las notificaciones push configuradas."
    : state === "ios-install"
      ? "En iPhone: abre el tablero en Safari, Compartir → «Agregar a pantalla de inicio», y actívalas desde la app."
      : state === "unsupported"
        ? "Este navegador no soporta notificaciones push."
        : state === "denied"
          ? "Las bloqueaste en este navegador: habilítalas en su configuración del sitio."
          : state === "on"
            ? `Llegan a este dispositivo${view.pushDevices > 1 ? ` y a ${view.pushDevices - 1} más` : ""}.`
            : view.pushDevices > 0
              ? `Llegan a ${view.pushDevices} dispositivo(s) tuyos; este no.`
              : "Desactivadas.";

  return (
    <li className="flex items-start gap-3 px-3.5 py-3">
      <div className="min-w-0 flex-1">
        <p className="text-[13px] font-medium text-fg-strong">Este dispositivo</p>
        <p className="text-xs text-fg-muted">{hint}</p>
      </div>
      <Switch
        checked={state === "on"}
        hideLabel
        label="Notificaciones en este dispositivo"
        disabled={pending || state === "loading" || !view.pushPublicKey || state === "unsupported" || state === "ios-install" || state === "denied"}
        onChange={(on) => (on ? enable() : disable())}
      />
    </li>
  );
}
