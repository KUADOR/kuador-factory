"use client";

import { Send } from "lucide-react";
import { useEffect, useRef, useState, useTransition } from "react";

import { notificationsAction, telegramLinkAction, testNotificationsAction, unlinkTelegramAction, updateNotificationsAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { Switch } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";
import type { NotificationSettings, NotificationView } from "@/lib/types";

const EVENTS: { key: "onAssigned" | "onGates" | "onPlans"; label: string; hint: string }[] = [
  { key: "onAssigned", label: "Te asignan un issue", hint: "Incluye cuando un agente te pide una decisión." },
  { key: "onGates", label: "Algo espera tu aprobación", hint: "Puertas como Aprobación, Revisión de PR o Release." },
  { key: "onPlans", label: "Un reparto de tareas espera tu aprobación", hint: "Las tareas de una épica antes de arrancar." },
];

/** Canales y avisos de la persona. Cada cambio se guarda al momento. */
export function NotificationSettingsForm({ initial }: { initial: NotificationView }) {
  const [view, setView] = useState(initial);
  const [pending, start] = useTransition();
  const waiting = useRef<ReturnType<typeof setInterval> | null>(null);
  const s = view.settings;

  useEffect(() => () => {
    if (waiting.current) clearInterval(waiting.current);
  }, []);

  const save = (next: Partial<NotificationSettings>) =>
    start(async () => {
      const merged = { ...s, ...next };
      const r = await updateNotificationsAction({
        email: merged.email,
        telegram: merged.telegram,
        onAssigned: merged.onAssigned,
        onGates: merged.onGates,
        onPlans: merged.onPlans,
      });
      if (r.ok) setView(r.data);
      else notify.error("No se pudieron guardar tus avisos", r.error);
    });

  const connectTelegram = () =>
    start(async () => {
      const r = await telegramLinkAction();
      if (!r.ok) {
        notify.error("No se pudo conectar Telegram", r.error);
        return;
      }
      window.open(r.data.url, "_blank", "noopener");
      // Mientras la persona pulsa «Iniciar» en Telegram, se revisa si ya quedó vinculado (hasta 2 minutos).
      let tries = 0;
      if (waiting.current) clearInterval(waiting.current);
      waiting.current = setInterval(async () => {
        tries++;
        const v = await notificationsAction();
        if (v.ok && v.data.settings.telegramChatId != null) {
          setView(v.data);
          notify.ok("Telegram conectado");
          if (waiting.current) clearInterval(waiting.current);
        } else if (tries > 60 && waiting.current) clearInterval(waiting.current);
      }, 2000);
    });

  return (
    <div className="flex flex-col gap-5">
      <ul className="flex flex-col divide-y divide-line rounded-md border border-line">
        <li className="flex items-start gap-3 px-3.5 py-3">
          <div className="min-w-0 flex-1">
            <p className="text-[13px] font-medium text-fg-strong">Correo</p>
            <p className="text-xs text-fg-muted">
              {view.emailAvailable ? (view.email ? `A ${view.email}.` : "Tu cuenta no tiene correo.") : "El servidor todavía no tiene correo configurado."}
            </p>
          </div>
          <Switch checked={s.email && view.emailAvailable} hideLabel label="Avisos por correo" disabled={pending || !view.emailAvailable} onChange={(on) => save({ email: on })} />
        </li>
        <li className="flex items-start gap-3 px-3.5 py-3">
          <div className="min-w-0 flex-1">
            <p className="text-[13px] font-medium text-fg-strong">Telegram</p>
            {!view.telegramAvailable ? (
              <p className="text-xs text-fg-muted">El servidor todavía no tiene un bot de Telegram configurado.</p>
            ) : s.telegramChatId != null ? (
              <p className="text-xs text-fg-muted">
                Conectado a @{view.telegramBot}.{" "}
                <button type="button" className="k-link" disabled={pending} onClick={() => start(async () => {
                  const r = await unlinkTelegramAction();
                  if (r.ok) setView(r.data);
                  else notify.error("No se pudo desconectar", r.error);
                })}>
                  Desconectar
                </button>
              </p>
            ) : (
              <div className="mt-1.5 flex flex-col items-start gap-1.5">
                <p className="text-xs text-fg-muted">Abre el bot @{view.telegramBot} y pulsa «Iniciar»: el enlace sirve una vez durante 15 minutos.</p>
                <Button size="sm" tone="secondary" loading={pending} onClick={connectTelegram}>
                  Conectar Telegram
                </Button>
              </div>
            )}
          </div>
          <Switch
            checked={s.telegram && s.telegramChatId != null}
            hideLabel
            label="Avisos por Telegram"
            disabled={pending || s.telegramChatId == null}
            onChange={(on) => save({ telegram: on })}
          />
        </li>
      </ul>

      <div className="flex flex-col gap-2">
        <p className="text-xs font-medium text-fg-muted">Qué te avisamos</p>
        <ul className="flex flex-col divide-y divide-line rounded-md border border-line">
          {EVENTS.map((e) => (
            <li key={e.key} className="flex items-start gap-3 px-3.5 py-3">
              <div className="min-w-0 flex-1">
                <p className="text-[13px] text-fg-strong">{e.label}</p>
                <p className="text-xs text-fg-muted">{e.hint}</p>
              </div>
              <Switch checked={s[e.key]} hideLabel label={e.label} disabled={pending} onChange={(on) => save({ [e.key]: on })} />
            </li>
          ))}
        </ul>
      </div>

      <div className="flex items-center justify-end">
        <Button
          size="sm"
          tone="ghost"
          icon={Send}
          disabled={pending}
          onClick={() =>
            start(async () => {
              const r = await testNotificationsAction();
              if (r.ok) notify.ok("Aviso de prueba enviado", r.data.channels.map((c) => (c === "EMAIL" ? "Correo" : "Telegram")).join(" y "));
              else notify.error("No se pudo enviar la prueba", r.error);
            })
          }
        >
          Enviar un aviso de prueba
        </Button>
      </div>
    </div>
  );
}
