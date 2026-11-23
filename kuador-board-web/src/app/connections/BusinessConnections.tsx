"use client";

import { Plus, RefreshCw } from "lucide-react";
import { useEffect, useState, useTransition } from "react";

import {
  deleteConnectionAction,
  refreshConnectionAction,
  saveConnectionAction,
  setConnectionBoardsAction,
  setConnectionWritesAction,
  startConnectionLoginAction,
} from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { ChipPicker } from "@/components/ui/ChipPicker";
import { ConfirmDialog } from "@/components/ui/Dialog";
import { Field, PasswordInput, Switch, TextInput } from "@/components/ui/Field";
import { Badge, Section } from "@/components/ui/Section";
import { notify } from "@/components/ui/Toaster";
import type { BusinessConnection, ConnectionLogin, Connector } from "@/lib/types";

const BLANK = { key: "", name: "", url: "", secret: "", login: false, clientId: "", clientSecret: "", scope: "", connector: null as Connector | null };

/** «Agenda de la clínica» → AGENDA_DE_LA_CLINICA (la clave con la que los agentes ven sus herramientas). */
function keyFrom(name: string) {
  return name
    .normalize("NFD")
    .replace(/[̀-ͯ]/g, "")
    .toUpperCase()
    .replace(/[^A-Z0-9]+/g, "_")
    .replace(/^[^A-Z]+|_+$/g, "")
    .slice(0, 24);
}

/**
 * Sistemas de la empresa que los agentes pueden usar (correo, agenda, facturación): cada uno es un servidor MCP. Lo que
 * solo lee se puede usar apenas se asigna a un tablero; lo que cambia datos, solo si se habilita aquí.
 */
export function BusinessConnections({
  connections: initial,
  directory,
  boards,
  done,
  error: failed,
}: {
  connections: BusinessConnection[];
  /** Sistemas conocidos que se eligen de una lista. */
  directory: Connector[];
  boards: { key: string; name: string }[];
  /** Nombre del sistema en el que se acaba de iniciar sesión (vuelta del sistema). */
  done?: string | null;
  error?: string | null;
}) {
  const [connections, setConnections] = useState(initial);
  const [draft, setDraft] = useState<typeof BLANK | null>(null);
  const [picking, setPicking] = useState(false);
  const [search, setSearch] = useState("");
  const [removing, setRemoving] = useState<BusinessConnection | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();

  useEffect(() => {
    if (done) notify.ok(`${done} conectado`, "Elige en qué tableros se usa.");
    if (failed) notify.error("No se pudo iniciar sesión", failed);
  }, [done, failed]);

  /** Lleva el navegador al sistema para iniciar sesión; al volver, la conexión queda lista. */
  const login = (key: string, body: ConnectionLogin) =>
    start(async () => {
      const r = await startConnectionLoginAction(key, body);
      if (!r.ok) {
        if (draft) setError(r.error);
        else notify.error("No se pudo iniciar sesión", r.error);
        return;
      }
      window.location.assign(r.data.authorizationUrl);
    });

  const replace = (c: BusinessConnection) => setConnections((all) => (all.some((x) => x.key === c.key) ? all.map((x) => (x.key === c.key ? c : x)) : [...all, c]));

  const create = () =>
    start(async () => {
      if (!draft) return;
      if (draft.login) {
        return login(draft.key || keyFrom(draft.name), {
          name: draft.name,
          url: draft.url,
          clientId: draft.clientId.trim() || null,
          clientSecret: draft.clientSecret || null,
          scope: draft.scope.trim() || null,
          connector: draft.connector?.key ?? null,
        });
      }
      const r = await saveConnectionAction(draft.key || keyFrom(draft.name), { name: draft.name, url: draft.url, header: null, secret: draft.secret || null });
      if (!r.ok) return setError(r.error);
      replace(r.data);
      setDraft(null);
      setError(null);
      notify.ok(`${r.data.name} conectado`, `${r.data.tools.length} herramientas. Elige en qué tableros se usa.`);
    });

  const apply = (work: () => Promise<{ ok: true; data: BusinessConnection } | { ok: false; error: string }>, done?: string) =>
    start(async () => {
      const r = await work();
      if (r.ok) {
        replace(r.data);
        if (done) notify.ok(done);
      } else notify.error("No se pudo guardar", r.error);
    });

  return (
    <Section
      title="Sistemas de la empresa"
      description="Lo que los agentes pueden consultar o usar: correo, agenda, facturación. Se conectan por MCP."
      actions={
        !draft && !picking && (
          <Button size="sm" icon={Plus} onClick={() => (directory.length > 0 ? setPicking(true) : setDraft(BLANK))}>
            Conectar
          </Button>
        )
      }
    >
      <div className="flex flex-col gap-5">
        {picking && (
          <div className="flex flex-col gap-3">
            <TextInput type="search" aria-label="Buscar un sistema" placeholder="Buscar: correo, agenda, pagos…" value={search} onChange={(e) => setSearch(e.target.value)} />
            <ul className="flex flex-col">
              {directory
                .filter((d) => `${d.name} ${d.category} ${d.description}`.toLowerCase().includes(search.trim().toLowerCase()))
                .map((d) => {
                  const has = connections.some((c) => c.key === d.key);
                  return (
                    <li key={d.key} className="flex items-center gap-3 border-t border-line py-2 first:border-t-0">
                      <span className="min-w-0 flex-1 text-[13px]">
                        <span className="font-medium text-fg-strong">{d.name}</span> <span className="text-fg-faint">{d.category}</span>
                        <span className="block truncate text-fg-muted">{d.description}</span>
                      </span>
                      {has ? (
                        <Badge tone="neutral">Conectado</Badge>
                      ) : (
                        <Button
                          size="sm"
                          loading={pending}
                          onClick={() => {
                            if (d.ready) return login(d.key, { name: d.name, url: null, clientId: null, clientSecret: null, scope: null, connector: d.key });
                            setPicking(false);
                            setDraft({ ...BLANK, key: d.key, name: d.name, url: d.url, login: true, connector: d });
                          }}
                        >
                          Conectar
                        </Button>
                      )}
                    </li>
                  );
                })}
              <li className="flex items-center gap-3 border-t border-line py-2">
                <span className="min-w-0 flex-1 text-[13px]">
                  <span className="font-medium text-fg-strong">Otro sistema</span>
                  <span className="block text-fg-muted">Cualquier servidor MCP: el sistema propio de la empresa u otro que no esté en la lista.</span>
                </span>
                <Button size="sm" onClick={() => { setPicking(false); setDraft(BLANK); }}>Conectar</Button>
              </li>
            </ul>
            <div className="flex justify-end">
              <Button tone="ghost" onClick={() => { setPicking(false); setSearch(""); }}>Cancelar</Button>
            </div>
          </div>
        )}

        {draft && (
          <form className="flex flex-col gap-3" onSubmit={(e) => { e.preventDefault(); create(); }}>
            <Field label="Nombre">
              {(id) => <TextInput id={id} required maxLength={80} value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} placeholder="Agenda de la clínica" />}
            </Field>
            <Field label="Dirección del servidor MCP" error={error}>
              {(id) => <TextInput id={id} required type="url" maxLength={500} value={draft.url} onChange={(e) => setDraft({ ...draft, url: e.target.value })} placeholder="https://…/mcp" />}
            </Field>
            {!draft.connector && <Switch checked={draft.login} label="Pide iniciar sesión" onChange={(on) => setDraft({ ...draft, login: on })} />}
            {draft.connector && (
              <p className="text-[13px] text-fg-muted">
                {draft.connector.name} pide una aplicación registrada a nombre de la empresa. Regístrala en {draft.connector.name} con la dirección de retorno{" "}
                <span className="font-mono text-xs">{typeof window === "undefined" ? "" : window.location.origin}/connections/oauth/callback</span> y pega aquí sus datos.
                {draft.connector.note ? ` ${draft.connector.note}` : ""}
              </p>
            )}
            {!draft.login && (
              <Field label="Credencial" hint="Se guarda cifrada y no se vuelve a mostrar. Vacía si el servidor no la pide.">
                {(id) => <PasswordInput id={id} autoComplete="off" value={draft.secret} onChange={(e) => setDraft({ ...draft, secret: e.target.value })} />}
              </Field>
            )}
            {draft.login && (
              <details className="text-[13px] text-fg-muted" open={draft.connector != null}>
                <summary className="cursor-pointer select-none">El sistema ya tiene una aplicación registrada</summary>
                <div className="mt-3 flex flex-col gap-3">
                  <Field label="Identificador de la aplicación" hint="Solo si el sistema no deja que el tablero se registre solo (por ejemplo, Microsoft).">
                    {(id) => <TextInput id={id} maxLength={300} autoComplete="off" value={draft.clientId} onChange={(e) => setDraft({ ...draft, clientId: e.target.value })} />}
                  </Field>
                  <Field label="Secreto de la aplicación">
                    {(id) => <PasswordInput id={id} autoComplete="off" value={draft.clientSecret} onChange={(e) => setDraft({ ...draft, clientSecret: e.target.value })} />}
                  </Field>
                  <Field label="Permisos" hint="Separados por espacio. Vacío: los que sugiere el sistema.">
                    {(id) => <TextInput id={id} maxLength={1000} value={draft.scope} onChange={(e) => setDraft({ ...draft, scope: e.target.value })} placeholder="Mail.Read offline_access" />}
                  </Field>
                </div>
              </details>
            )}
            <div className="flex justify-end gap-2">
              <Button tone="ghost" onClick={() => { setDraft(null); setError(null); }}>Cancelar</Button>
              <Button type="submit" tone="primary" loading={pending} disabled={!draft.name.trim() || !draft.url.trim() || (draft.connector != null && !draft.clientId.trim())}>{draft.login ? "Iniciar sesión" : "Conectar"}</Button>
            </div>
          </form>
        )}

        {!draft && !picking && connections.length === 0 && <p className="text-[13px] text-fg-muted">Todavía no hay ninguno conectado.</p>}

        {connections.map((c) => {
          const writes = c.tools.filter((t) => !t.readOnly);
          const reads = c.tools.length - writes.length;
          return (
            <div key={c.key} className="flex flex-col gap-3 border-t border-line pt-4 first:border-t-0 first:pt-0">
              <div className="flex flex-wrap items-center gap-2">
                <p className="min-w-0 flex-1 text-[13px] font-medium text-fg-strong">
                  {c.name} <span className="font-mono text-xs font-normal text-fg-faint">{c.url}</span>
                </p>
                {c.connected ? <Badge tone="neutral">{reads} de consulta</Badge> : <Badge tone="warn">Falta iniciar sesión</Badge>}
                {c.auth === "OAUTH" && (
                  <Button size="sm" tone="ghost" loading={pending} onClick={() => login(c.key, { name: c.name, url: c.url, clientId: null, clientSecret: null, scope: null })}>
                    {c.connected ? "Volver a iniciar sesión" : "Iniciar sesión"}
                  </Button>
                )}
                {c.connected && (
                  <Button size="sm" tone="ghost" icon={RefreshCw} loading={pending} onClick={() => apply(() => refreshConnectionAction(c.key), "Herramientas actualizadas")}>
                    Actualizar
                  </Button>
                )}
                <Button size="sm" tone="ghost" onClick={() => setRemoving(c)}>Quitar</Button>
              </div>
              <div className="flex flex-col gap-1.5">
                <span className="text-xs font-medium text-fg-muted">Tableros que lo usan</span>
                <ChipPicker
                  value={c.boards.map((b) => b.projectKey)}
                  options={boards.map((b) => ({ value: b.key, label: b.name }))}
                  onChange={(keys) =>
                    apply(() => setConnectionBoardsAction(c.key, keys.map((k) => ({ projectKey: k, roles: c.boards.find((b) => b.projectKey === k)?.roles ?? [] }))))
                  }
                  placeholder="Agregar tablero"
                  ariaLabel={`Tableros que usan ${c.name}`}
                  empty="Ninguno: los agentes aún no lo ven."
                />
              </div>
              {writes.length > 0 && (
                <div className="flex flex-col gap-2">
                  <span className="text-xs font-medium text-fg-muted">Acciones que cambian datos (apagadas, el agente deja el borrador y lo hace una persona)</span>
                  {writes.map((t) => (
                    <div key={t.name} className="flex items-center justify-between gap-4">
                      <span className="min-w-0 text-[13px] text-fg">
                        <span className="font-mono text-xs">{t.name}</span> <span className="text-fg-muted">{t.description}</span>
                      </span>
                      <Switch
                        checked={t.allowed}
                        disabled={pending}
                        label={`Permitir ${t.name}`}
                        hideLabel
                        onChange={(on) => {
                          const names = writes.filter((w) => (w.name === t.name ? on : w.allowed)).map((w) => w.name);
                          apply(() => setConnectionWritesAction(c.key, names));
                        }}
                      />
                    </div>
                  ))}
                </div>
              )}
            </div>
          );
        })}
      </div>
      <ConfirmDialog
        open={removing != null}
        onCancel={() => setRemoving(null)}
        onConfirm={() =>
          start(async () => {
            if (!removing) return;
            const r = await deleteConnectionAction(removing.key);
            if (r.ok) setConnections((all) => all.filter((x) => x.key !== removing.key));
            else notify.error("No se pudo quitar", r.error);
            setRemoving(null);
          })
        }
        title={`Quitar ${removing?.name ?? ""}`}
        message="Los agentes dejan de ver sus herramientas en todos los tableros. La credencial se borra."
        confirmLabel="Quitar"
        pending={pending}
      />
    </Section>
  );
}
