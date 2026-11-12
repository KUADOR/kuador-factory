"use client";

import { Copy } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import {
  connectTelegramBotAction,
  disconnectTelegramBotAction,
  removeTelegramGroupAction,
  telegramGroupCodeAction,
  telegramSetupAction,
  updateTelegramGroupAction,
} from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { ConfirmDialog } from "@/components/ui/Dialog";
import { Field, PasswordInput, Switch } from "@/components/ui/Field";
import { Section } from "@/components/ui/Section";
import { notify } from "@/components/ui/Toaster";
import type { TelegramGroup, TelegramSetup } from "@/lib/types";

/**
 * Telegram del espacio: el bot propio de la empresa (lo crea con @BotFather; el token se guarda cifrado) y los grupos
 * que reciben avisos del equipo. Sin bot propio, se usa el de la plataforma si existe.
 */
export function TelegramConnection({ setup: initial }: { setup: TelegramSetup }) {
  const router = useRouter();
  const [setup, setSetup] = useState(initial);
  const [token, setToken] = useState("");
  const [editing, setEditing] = useState(!initial.ownBot && !initial.platformBot);
  const [code, setCode] = useState<{ command: string; bot: string } | null>(null);
  const [confirmRemove, setConfirmRemove] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();
  const bot = setup.ownBot ?? setup.platformBot;

  const connect = () =>
    start(async () => {
      const r = await connectTelegramBotAction(token);
      if (!r.ok) return setError(r.error);
      setSetup(r.data);
      setToken("");
      setEditing(false);
      setError(null);
      notify.ok(`Bot @${r.data.ownBot} conectado`, "Cada persona vuelve a conectar su Telegram en Mi cuenta.");
    });

  const linkGroup = () =>
    start(async () => {
      const r = await telegramGroupCodeAction();
      if (!r.ok) {
        notify.error("No se pudo crear el código", r.error);
        return;
      }
      setCode(r.data);
      // Mientras la persona escribe el comando en el grupo, se revisa si ya quedó vinculado (hasta 2 minutos).
      const before = setup.groups.length;
      for (let i = 0; i < 40; i++) {
        await new Promise((ok) => setTimeout(ok, 3000));
        const s = await telegramSetupAction();
        if (s.ok && s.data.groups.length > before) {
          setSetup(s.data);
          setCode(null);
          notify.ok("Grupo vinculado");
          router.refresh();
          return;
        }
      }
    });

  return (
    <Section
      title="Telegram"
      description="Avisos del tablero por Telegram con el bot de tu empresa: a cada persona (lo conecta en Mi cuenta → Notificaciones) y a los grupos que vincules."
    >
      <div className="flex flex-col gap-5">
        {!editing ? (
          <div className="flex flex-wrap items-center gap-3 text-[13px]">
            <p className="min-w-0 flex-1 text-fg">
              {setup.ownBot ? (
                <>Bot de la empresa: <span className="font-medium text-fg-strong">@{setup.ownBot}</span></>
              ) : (
                <>Usa el bot de la plataforma <span className="font-medium text-fg-strong">@{setup.platformBot}</span>. Puedes conectar el de tu empresa.</>
              )}
            </p>
            <Button size="sm" onClick={() => setEditing(true)}>{setup.ownBot ? "Cambiar bot" : "Conectar el bot de la empresa"}</Button>
            {setup.ownBot && (
              <Button size="sm" tone="ghost" onClick={() => setConfirmRemove(true)}>Quitar</Button>
            )}
          </div>
        ) : (
          <form className="flex flex-col gap-3" onSubmit={(e) => { e.preventDefault(); connect(); }}>
            <ol className="list-decimal space-y-1 pl-5 text-[13px] text-fg-muted">
              <li>En Telegram, abre <span className="font-medium text-fg">@BotFather</span> y envía <code className="font-mono text-xs">/newbot</code>.</li>
              <li>Elige el nombre y el usuario del bot (termina en «bot»).</li>
              <li>Copia el token que te entrega y pégalo aquí. Se guarda cifrado.</li>
            </ol>
            <Field label="Token del bot" error={error}>
              {(id) => <PasswordInput id={id} required autoComplete="off" value={token} onChange={(e) => setToken(e.target.value)} placeholder="123456789:AA…" />}
            </Field>
            {bot && <p className="text-xs text-fg-faint">Al cambiar de bot, las personas y los grupos vinculados al anterior se vuelven a vincular.</p>}
            <div className="flex justify-end gap-2">
              {bot && <Button tone="ghost" onClick={() => { setEditing(false); setError(null); }}>Cancelar</Button>}
              <Button type="submit" tone="primary" loading={pending}>Conectar bot</Button>
            </div>
          </form>
        )}

        {bot && !editing && (
          <div className="flex flex-col gap-3 border-t border-line pt-4">
            <div className="flex items-center justify-between gap-3">
              <h3 className="text-[13px] font-medium text-fg-strong">Grupos</h3>
              <Button size="sm" loading={pending && !!code} onClick={linkGroup}>Vincular grupo</Button>
            </div>
            {code && (
              <div className="flex flex-col gap-2 rounded-md border border-line bg-sunken p-3 text-[13px]">
                <p className="text-fg-muted">
                  Agrega a <span className="font-medium text-fg">@{code.bot}</span> al grupo y envía este mensaje en el grupo. Sirve una vez durante 15 minutos.
                </p>
                <div className="flex items-center gap-2">
                  <code className="min-w-0 flex-1 truncate rounded bg-surface px-2 py-1.5 font-mono text-xs text-fg-strong">{code.command}</code>
                  <Button
                    size="sm"
                    icon={Copy}
                    iconOnly
                    label="Copiar"
                    onClick={() => void navigator.clipboard.writeText(code.command).then(() => notify.ok("Copiado"))}
                  />
                </div>
              </div>
            )}
            {setup.groups.length === 0 && !code ? (
              <p className="text-[13px] text-fg-faint">Ningún grupo todavía. Un grupo recibe un aviso por evento, no uno por persona.</p>
            ) : (
              <ul className="divide-y divide-line rounded-md border border-line">
                {setup.groups.map((g) => (
                  <GroupRow key={g.id} group={g} onRemoved={() => setSetup({ ...setup, groups: setup.groups.filter((x) => x.id !== g.id) })} />
                ))}
              </ul>
            )}
          </div>
        )}
      </div>

      <ConfirmDialog
        open={confirmRemove}
        onCancel={() => setConfirmRemove(false)}
        onConfirm={() =>
          start(async () => {
            const r = await disconnectTelegramBotAction();
            setConfirmRemove(false);
            if (!r.ok) {
              notify.error("No se pudo quitar el bot", r.error);
              return;
            }
            setSetup(r.data);
            setEditing(!r.data.platformBot);
            notify.ok("Bot quitado");
          })
        }
        title="Quitar el bot de la empresa"
        message="Las personas y los grupos dejan de recibir avisos por este bot y tendrán que vincularse de nuevo."
        confirmLabel="Quitar bot"
        pending={pending}
      />
    </Section>
  );
}

function GroupRow({ group, onRemoved }: { group: TelegramGroup; onRemoved: () => void }) {
  const [g, setG] = useState(group);
  const [pending, start] = useTransition();
  const save = (patch: Partial<TelegramGroup>) => {
    const next = { ...g, ...patch };
    setG(next);
    start(async () => {
      const r = await updateTelegramGroupAction(g.id, { onAssigned: next.onAssigned, onGates: next.onGates, onPlans: next.onPlans });
      if (!r.ok) {
        setG(g);
        notify.error("No se pudo guardar", r.error);
      }
    });
  };
  return (
    <li className="flex flex-wrap items-center gap-x-5 gap-y-2 px-3.5 py-3">
      <span className="min-w-0 flex-1 truncate text-[13px] font-medium text-fg-strong">{g.title}</span>
      <Switch checked={g.onGates} disabled={pending} label="Aprobaciones" onChange={(v) => save({ onGates: v })} />
      <Switch checked={g.onPlans} disabled={pending} label="Repartos" onChange={(v) => save({ onPlans: v })} />
      <Switch checked={g.onAssigned} disabled={pending} label="Asignaciones" onChange={(v) => save({ onAssigned: v })} />
      <Button
        size="sm"
        tone="ghost"
        disabled={pending}
        onClick={() =>
          start(async () => {
            const r = await removeTelegramGroupAction(g.id);
            if (r.ok) onRemoved();
            else notify.error("No se pudo desvincular", r.error);
          })
        }
      >
        Desvincular
      </Button>
    </li>
  );
}
