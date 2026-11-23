"use client";

import { useEffect, useMemo, useState, useTransition } from "react";

import { readApiSpecAction, saveApiConnectionAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { Field, PasswordInput, SelectInput, TextArea, TextInput } from "@/components/ui/Field";
import type { ApiSpec, BusinessConnection, DemoErpInfo } from "@/lib/types";

/** «ERP de compras» → ERP_DE_COMPRAS (la clave con la que los agentes ven sus herramientas). */
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
 * Conectar un sistema por su API: se lee su documentación (OpenAPI o Swagger), se eligen las operaciones que el agente
 * puede usar y se pone la credencial. Las que cambian datos quedan apagadas hasta habilitarlas en la conexión.
 */
export function ApiForm({ preset, onDone, onCancel }: { preset?: DemoErpInfo | null; onDone: (c: BusinessConnection) => void; onCancel: () => void }) {
  const [name, setName] = useState(preset?.name ?? "");
  const [source, setSource] = useState<"url" | "text">("url");
  const [specUrl, setSpecUrl] = useState(preset?.specUrl ?? "");
  const [specText, setSpecText] = useState("");
  const [spec, setSpec] = useState<ApiSpec | null>(null);
  const [baseUrl, setBaseUrl] = useState("");
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [filter, setFilter] = useState("");
  const [header, setHeader] = useState(preset?.header ?? "Authorization");
  const [prefix, setPrefix] = useState(preset ? "" : "Bearer ");
  const [credential, setCredential] = useState(preset?.credential ?? "");
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();

  const read = () =>
    start(async () => {
      const r = await readApiSpecAction(source === "url" ? { url: specUrl.trim(), text: null } : { url: null, text: specText });
      if (!r.ok) return setError(r.error);
      setError(null);
      setSpec(r.data);
      setBaseUrl(r.data.baseUrl);
      if (!name.trim()) setName(r.data.title.slice(0, 80));
      setSelected(new Set(preset ? preset.operations.filter((o) => r.data.operations.some((x) => x.name === o)) : []));
    });

  // El ERP de demostración se lee solo: ya se sabe dónde está su documentación.
  useEffect(() => {
    if (preset) read();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const visible = useMemo(() => {
    const f = filter.trim().toLowerCase();
    return (spec?.operations ?? []).filter((o) => !f || `${o.name} ${o.description} ${o.path}`.toLowerCase().includes(f));
  }, [spec, filter]);

  const toggle = (op: string, on: boolean) =>
    setSelected((s) => {
      const next = new Set(s);
      if (on) next.add(op);
      else next.delete(op);
      return next;
    });

  const save = () =>
    start(async () => {
      const r = await saveApiConnectionAction(keyFrom(name), {
        name: name.trim(),
        specUrl: source === "url" ? specUrl.trim() : null,
        specText: source === "text" ? specText : null,
        baseUrl: baseUrl.trim() || null,
        header: header.trim() || "Authorization",
        prefix,
        credential: credential || null,
        operations: [...selected],
      });
      if (!r.ok) return setError(r.error);
      onDone(r.data);
    });

  const reads = visible.filter((o) => o.readOnly);
  const writes = visible.filter((o) => !o.readOnly);

  return (
    <form className="flex flex-col gap-3" onSubmit={(e) => { e.preventDefault(); if (spec) save(); else read(); }}>
      <p className="text-[13px] text-fg-muted">
        Para sistemas con API documentada (OpenAPI o Swagger), como el ERP de la empresa. El agente solo usa las operaciones que elijas; las que cambian datos quedan apagadas hasta que las habilites.
      </p>
      {preset && preset.samples.length > 0 && (
        <p className="rounded-md bg-hover px-3 py-2 text-[13px] text-fg">
          Facturas de ejemplo para enviar al buzón:{" "}
          {preset.samples.map((s, i) => (
            <span key={s.url}>
              {i > 0 && " · "}
              <a href={s.url} target="_blank" rel="noreferrer" className="k-link">
                {s.name}
              </a>
            </span>
          ))}
        </p>
      )}
      <Field label="Nombre">
        {(id) => <TextInput id={id} required maxLength={80} value={name} onChange={(e) => setName(e.target.value)} placeholder="ERP de compras" />}
      </Field>
      {!spec && (
        <>
          <SelectInput
            ariaLabel="De dónde sale la documentación"
            value={source}
            onChange={(v) => v && setSource(v)}
            options={[
              { value: "url", label: "Dirección de la documentación (openapi.json, swagger.json…)" },
              { value: "text", label: "Pegar la documentación (JSON o YAML)" },
            ]}
          />
          {source === "url" ? (
            <Field label="Dirección de la documentación" error={error}>
              {(id) => <TextInput id={id} required type="url" maxLength={500} value={specUrl} onChange={(e) => setSpecUrl(e.target.value)} placeholder="https://erp.empresa.com/api/openapi.json" />}
            </Field>
          ) : (
            <Field label="Documentación" error={error}>
              {(id) => <TextArea id={id} required rows={8} value={specText} onChange={(e) => setSpecText(e.target.value)} placeholder='{"openapi": "3.0.0", …}' />}
            </Field>
          )}
          <div className="flex justify-end gap-2">
            <Button tone="ghost" onClick={onCancel}>Cancelar</Button>
            <Button type="submit" tone="primary" loading={pending} disabled={source === "url" ? !specUrl.trim() : !specText.trim()}>
              Leer documentación
            </Button>
          </div>
        </>
      )}
      {spec && (
        <>
          <Field label="Dirección base de la API" hint={`De «${spec.title}». Cámbiala si la documentación apunta a otro ambiente.`}>
            {(id) => <TextInput id={id} required type="url" maxLength={500} value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} />}
          </Field>
          <div className="flex flex-col gap-2">
            <span className="text-xs font-medium text-fg-muted">
              Operaciones que puede usar el agente ({selected.size} de {spec.operations.length})
            </span>
            {spec.operations.length > 8 && <TextInput type="search" aria-label="Buscar operación" placeholder="Buscar: pedido, factura…" value={filter} onChange={(e) => setFilter(e.target.value)} />}
            <OperationList title="Consultan" items={reads} selected={selected} onToggle={toggle} />
            <OperationList title="Cambian datos (apagadas hasta habilitarlas)" items={writes} selected={selected} onToggle={toggle} />
          </div>
          <div className="grid gap-3 sm:grid-cols-[1fr_10rem]">
            <Field label="Cabecera de la credencial" hint="Authorization, X-API-Key…">
              {(id) => <TextInput id={id} maxLength={64} value={header} onChange={(e) => setHeader(e.target.value)} />}
            </Field>
            <Field label="Formato">
              {(id) => (
                <SelectInput
                  id={id}
                  value={prefix === "Bearer " ? "bearer" : "plain"}
                  onChange={(v) => setPrefix(v === "bearer" ? "Bearer " : "")}
                  options={[
                    { value: "bearer", label: "Bearer …" },
                    { value: "plain", label: "Solo la clave" },
                  ]}
                />
              )}
            </Field>
          </div>
          <Field label="Credencial" hint="Se guarda cifrada y no se vuelve a mostrar. Usa una de un usuario solo para Kuador." error={error}>
            {(id) => <PasswordInput id={id} autoComplete="off" value={credential} onChange={(e) => setCredential(e.target.value)} />}
          </Field>
          <div className="flex justify-end gap-2">
            <Button tone="ghost" onClick={onCancel}>Cancelar</Button>
            <Button type="submit" tone="primary" loading={pending} disabled={!name.trim() || selected.size === 0 || !baseUrl.trim()}>
              Conectar
            </Button>
          </div>
        </>
      )}
    </form>
  );
}

function OperationList({
  title,
  items,
  selected,
  onToggle,
}: {
  title: string;
  items: ApiSpec["operations"];
  selected: Set<string>;
  onToggle: (name: string, on: boolean) => void;
}) {
  if (items.length === 0) return null;
  return (
    <fieldset className="flex flex-col gap-1 rounded-md border border-line p-2">
      <legend className="px-1 text-xs text-fg-faint">{title}</legend>
      <div className="flex max-h-64 flex-col gap-1 overflow-y-auto">
        {items.map((o) => (
          <label key={o.name} className="flex cursor-pointer items-start gap-2 rounded px-1 py-1 text-[13px] hover:bg-hover">
            <input type="checkbox" className="mt-0.5" checked={selected.has(o.name)} onChange={(e) => onToggle(o.name, e.target.checked)} />
            <span className="min-w-0">
              <span className="font-mono text-xs text-fg-strong">{o.name}</span>{" "}
              <span className="text-fg-muted">{o.description}</span>
            </span>
          </label>
        ))}
      </div>
    </fieldset>
  );
}
