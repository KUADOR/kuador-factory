"use client";

import { Inbox, Pencil, Plus, RefreshCw, Trash } from "lucide-react";
import Link from "next/link";
import { useState, useTransition } from "react";
import { clsx } from "clsx";

import { checkInboxAction, deleteInboxAction, saveInboxAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { Field, SelectInput, Switch, TextInput } from "@/components/ui/Field";
import { SidePanel } from "@/components/ui/SidePanel";
import { notify } from "@/components/ui/Toaster";
import type { MailInbox, MailInboxInput, Mailbox } from "@/lib/types";

const when = (iso: string | null) => (iso ? new Date(iso).toLocaleString("es", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }) : "—");

/**
 * Bandejas de correo del tablero: el buzón (Outlook, Gmail o correo con contraseña) se revisa cada minuto y cada correo
 * nuevo que cumple los filtros se vuelve una tarjeta; la toma el agente de la etapa como cualquier otra.
 */
export function InboxEditor({
  projectKey,
  inboxes,
  mailboxes,
  columns,
  assignees,
}: {
  projectKey: string;
  inboxes: MailInbox[];
  mailboxes: Mailbox[];
  columns: { key: string; name: string }[];
  assignees: { id: string; name: string; agent: boolean }[];
}) {
  const [editing, setEditing] = useState<{ id: string | null; input: MailInboxInput } | null>(null);
  const [pending, start] = useTransition();
  const column = (key: string | null) => columns.find((c) => c.key === key)?.name ?? columns[0]?.name ?? "la primera columna";

  if (mailboxes.length === 0 && inboxes.length === 0) {
    return (
      <div className="flex flex-col items-center gap-2 rounded-lg border border-dashed border-line-strong px-6 py-10 text-center">
        <Inbox className="size-6 text-fg-faint" aria-hidden />
        <p className="text-[14px] font-medium text-fg-strong">Primero conecta un buzón</p>
        <p className="text-[13px] text-fg-muted">
          En{" "}
          <Link href="/connections?ver=sistemas" className="k-link">
            Conexiones → Sistemas de la empresa
          </Link>{" "}
          escribe el correo de la empresa y conéctalo.
        </p>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex justify-end">
        <Button
          tone="primary"
          size="sm"
          icon={Plus}
          disabled={mailboxes.length === 0}
          onClick={() =>
            setEditing({
              id: null,
              input: { connection: mailboxes[0]?.key ?? "", folder: null, fromFilter: null, onlyAttachments: false, status: null, assigneeId: null, enabled: true, backfillDays: 0 },
            })
          }
        >
          Nueva bandeja
        </Button>
      </div>

      {inboxes.length === 0 ? (
        <div className="flex flex-col items-center gap-2 rounded-lg border border-dashed border-line-strong px-6 py-10 text-center">
          <Inbox className="size-6 text-fg-faint" aria-hidden />
          <p className="text-[14px] font-medium text-fg-strong">Este tablero todavía no recibe correo</p>
        </div>
      ) : (
        <ul className="flex flex-col gap-2.5">
          {inboxes.map((b) => (
            <li key={b.id} className={clsx("flex flex-col gap-2 rounded-lg border border-line bg-surface px-4 py-3", !b.enabled && "opacity-70")}>
              <div className="flex flex-wrap items-start gap-x-3 gap-y-1">
                <span className="min-w-0 flex-1">
                  <span className="block text-[14px] font-medium text-fg-strong">{b.connectionName}</span>
                  <span className="block text-xs text-fg-muted">
                    {[
                      b.folder ? `carpeta ${b.folder}` : "bandeja de entrada",
                      b.fromFilter ? `de ${b.fromFilter}` : null,
                      b.onlyAttachments ? "solo con adjuntos" : null,
                      `a ${column(b.status)}`,
                      b.assigneeName ? `para ${b.assigneeName}` : null,
                    ]
                      .filter(Boolean)
                      .join(" · ")}
                  </span>
                </span>
                <span className="text-xs text-fg-faint">{b.enabled ? `Revisado: ${when(b.lastCheckedAt)}` : "En pausa"}</span>
              </div>
              {b.lastError && <p className="text-xs text-danger">{b.lastError}</p>}
              <p className="text-xs text-fg-muted">
                {b.cardsCreated === 1 ? "1 tarjeta creada" : `${b.cardsCreated} tarjetas creadas`}
                {b.lastIssueKey && (
                  <>
                    {" "}
                    · última{" "}
                    <Link href={`/projects/${projectKey}?issue=${b.lastIssueKey}`} className="k-link font-mono">
                      {b.lastIssueKey}
                    </Link>
                  </>
                )}
              </p>
              <div className="flex flex-wrap gap-2">
                <Button
                  size="sm"
                  icon={RefreshCw}
                  loading={pending}
                  onClick={() =>
                    start(async () => {
                      const res = await checkInboxAction(projectKey, b.id);
                      if (!res.ok) notify.error("No se pudo revisar", res.error);
                      else if (res.data.error) notify.error("El buzón respondió un error", res.data.error);
                      else if (res.data.created.length === 0) notify.ok("Sin correos nuevos");
                      else notify.ok(res.data.created.length === 1 ? "1 tarjeta nueva" : `${res.data.created.length} tarjetas nuevas`, res.data.created.join(", "));
                    })
                  }
                >
                  Revisar ahora
                </Button>
                <Button
                  size="sm"
                  tone="ghost"
                  icon={Pencil}
                  onClick={() =>
                    setEditing({
                      id: b.id,
                      input: {
                        connection: b.connection,
                        folder: b.folder,
                        fromFilter: b.fromFilter,
                        onlyAttachments: b.onlyAttachments,
                        status: b.status,
                        assigneeId: b.assigneeId,
                        enabled: b.enabled,
                      },
                    })
                  }
                >
                  Editar
                </Button>
                <Button
                  size="sm"
                  tone="ghost"
                  icon={Trash}
                  iconOnly
                  label={`Quitar la bandeja de ${b.connectionName}`}
                  disabled={pending}
                  onClick={() =>
                    start(async () => {
                      if (!window.confirm(`¿Dejar de recibir el correo de «${b.connectionName}» en este tablero? Las tarjetas que ya creó se quedan.`)) return;
                      const res = await deleteInboxAction(projectKey, b.id);
                      if (res.ok) notify.ok("Bandeja quitada");
                      else notify.error("No se pudo quitar", res.error);
                    })
                  }
                />
              </div>
            </li>
          ))}
        </ul>
      )}

      <SidePanel open={editing != null} onClose={() => setEditing(null)} width="narrow" title={editing?.id ? "Editar bandeja" : "Nueva bandeja de correo"}>
        {editing && (
          <InboxForm
            key={editing.id ?? "new"}
            isNew={editing.id == null}
            initial={editing.input}
            mailboxes={mailboxes}
            columns={columns}
            assignees={assignees}
            pending={pending}
            onSave={(input) =>
              start(async () => {
                const res = await saveInboxAction(projectKey, editing.id, input);
                if (res.ok) {
                  notify.ok(editing.id ? "Bandeja actualizada" : "Bandeja creada", "Se revisa cada minuto; también puedes «Revisar ahora».");
                  setEditing(null);
                } else notify.error("No se pudo guardar", res.error);
              })
            }
          />
        )}
      </SidePanel>
    </div>
  );
}

function InboxForm({
  isNew,
  initial,
  mailboxes,
  columns,
  assignees,
  pending,
  onSave,
}: {
  isNew: boolean;
  initial: MailInboxInput;
  mailboxes: Mailbox[];
  columns: { key: string; name: string }[];
  assignees: { id: string; name: string; agent: boolean }[];
  pending: boolean;
  onSave: (input: MailInboxInput) => void;
}) {
  const [v, setV] = useState(initial);
  const set = <K extends keyof MailInboxInput>(k: K, value: MailInboxInput[K]) => setV((x) => ({ ...x, [k]: value }));
  return (
    <form
      className="flex flex-col gap-4 p-5"
      onSubmit={(e) => {
        e.preventDefault();
        onSave({ ...v, folder: v.folder?.trim() || null, fromFilter: v.fromFilter?.trim() || null });
      }}
    >
      <Field label="Buzón">
        {(id) => <SelectInput id={id} value={v.connection} onChange={(c) => c && set("connection", c)} options={mailboxes.map((m) => ({ value: m.key, label: m.name }))} />}
      </Field>
      <Field label="Carpeta" hint="Vacía: la bandeja de entrada. Sirve una carpeta o etiqueta donde una regla del correo deja las facturas.">
        {(id) => <TextInput id={id} maxLength={100} value={v.folder ?? ""} onChange={(e) => set("folder", e.target.value)} placeholder="Facturas" />}
      </Field>
      <Field label="Solo de estos remitentes" hint="Correos o dominios separados por coma. Vacío: de cualquiera.">
        {(id) => <TextInput id={id} maxLength={200} value={v.fromFilter ?? ""} onChange={(e) => set("fromFilter", e.target.value)} placeholder="@proveedor.com, facturas@otro.com" />}
      </Field>
      <Switch checked={v.onlyAttachments} label="Solo los que traen adjuntos" onChange={(on) => set("onlyAttachments", on)} />
      <Field label="Columna donde entra la tarjeta" hint="Si esa etapa tiene un agente, la toma solo.">
        {(id) => (
          <SelectInput
            id={id}
            value={v.status ?? ""}
            onChange={(c) => set("status", c || null)}
            options={[{ value: "", label: `La primera (${columns[0]?.name ?? "—"})` }, ...columns.map((c) => ({ value: c.key, label: c.name }))]}
          />
        )}
      </Field>
      <Field label="Asignar a" hint="Opcional: si no, la toma el agente de la etapa.">
        {(id) => (
          <SelectInput
            id={id}
            value={v.assigneeId ?? ""}
            onChange={(a) => set("assigneeId", a || null)}
            options={[{ value: "", label: "Según la etapa" }, ...assignees.map((a) => ({ value: a.id, label: a.agent ? `${a.name} (agente)` : a.name }))]}
          />
        )}
      </Field>
      {isNew && (
        <Field label="Correos que ya llegaron" hint="Para no perder los recientes al empezar.">
          {(id) => (
            <SelectInput
              id={id}
              value={String(v.backfillDays ?? 0)}
              onChange={(d) => set("backfillDays", Number(d ?? 0))}
              options={[
                { value: "0", label: "Solo los que lleguen desde ahora" },
                { value: "1", label: "También los del último día" },
                { value: "3", label: "También los de los últimos 3 días" },
                { value: "7", label: "También los de la última semana" },
              ]}
            />
          )}
        </Field>
      )}
      <Switch checked={v.enabled} label="Activa" onChange={(on) => set("enabled", on)} />
      <div className="flex justify-end">
        <Button type="submit" tone="primary" loading={pending} disabled={!v.connection}>
          {isNew ? "Crear bandeja" : "Guardar"}
        </Button>
      </div>
    </form>
  );
}
