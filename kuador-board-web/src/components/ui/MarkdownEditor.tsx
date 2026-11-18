"use client";

import { clsx } from "clsx";
import {
  Bold,
  Braces,
  CheckSquare,
  Code,
  FileText,
  Heading2,
  Heading3,
  Italic,
  Link2,
  List,
  ListOrdered,
  Minus,
  Pencil,
  Quote,
  Table,
} from "lucide-react";
import { useState } from "react";

import { Markdown } from "@/components/docs/Markdown";
import { DropdownMenu } from "@/components/ui/DropdownMenu";
import { TextArea } from "@/components/ui/Field";

/** Una plantilla para empezar la descripción (p. ej. historia de usuario con contrato JSON). */
export interface MarkdownTemplate {
  label: string;
  text: string;
}

const LANGUAGES = [
  { id: "json", label: "JSON" },
  { id: "sql", label: "SQL" },
  { id: "ts", label: "TypeScript" },
  { id: "java", label: "Java" },
  { id: "bash", label: "Terminal" },
  { id: "yaml", label: "YAML" },
  { id: "http", label: "HTTP" },
  { id: "", label: "Texto" },
];

/**
 * Editor de descripción al estilo Jira: barra con títulos, listas, tablas y bloques de código, vista previa y
 * plantillas. Guarda Markdown, que es lo mismo que leen los agentes (un bloque ```json llega como JSON). Al pegar JSON
 * lo convierte en un bloque de código; al pegar celdas de una planilla, en una tabla.
 */
export function MarkdownEditor({
  id,
  value,
  onChange,
  readOnly = false,
  startInView = false,
  rows = 8,
  placeholder,
  templates = [],
  hint,
}: {
  id?: string;
  value: string;
  onChange: (v: string) => void;
  readOnly?: boolean;
  /** Abre mostrando el texto formateado (como Jira); un clic en «Editar» pasa a escribir. */
  startInView?: boolean;
  rows?: number;
  placeholder?: string;
  templates?: MarkdownTemplate[];
  /** Ayuda que se muestra solo mientras se escribe. */
  hint?: React.ReactNode;
}) {
  const [mode, setMode] = useState<"view" | "write" | "preview">(readOnly || startInView ? "view" : "write");
  // El contenedor en estado (no en un ref): las acciones de la barra leen el textarea al ejecutarse.
  const [box, setBox] = useState<HTMLDivElement | null>(null);
  const area = () => box?.querySelector("textarea") ?? null;

  if (readOnly || mode === "view") {
    return (
      <div className="group relative rounded-md border border-line bg-surface">
        {value.trim() ? (
          <div className="max-h-[32rem] overflow-y-auto px-3.5 py-3" onDoubleClick={readOnly ? undefined : () => setMode("write")}>
            <Markdown content={value} />
          </div>
        ) : readOnly ? (
          <p className="px-3.5 py-3 text-[13px] text-fg-faint">Sin descripción.</p>
        ) : (
          <button type="button" onClick={() => setMode("write")} className="block w-full px-3.5 py-3 text-left text-[13px] text-fg-faint hover:text-fg-muted">
            Agregar una descripción…
          </button>
        )}
        {!readOnly && value.trim() && (
          <button
            type="button"
            onClick={() => setMode("write")}
            className="absolute top-2 right-2 inline-flex items-center gap-1 rounded-md border border-line bg-surface px-2 py-1 text-xs text-fg-muted opacity-100 hover:text-fg-strong sm:opacity-0 sm:group-focus-within:opacity-100 sm:group-hover:opacity-100"
          >
            <Pencil className="size-3" aria-hidden /> Editar
          </button>
        )}
      </div>
    );
  }

  /** Cambia el texto y deja la selección donde corresponde. */
  const apply = (next: string, selStart: number, selEnd = selStart) => {
    onChange(next);
    requestAnimationFrame(() => {
      const el = area();
      if (!el) return;
      el.focus();
      el.setSelectionRange(selStart, selEnd);
    });
  };

  const selection = () => {
    const el = area();
    return { start: el?.selectionStart ?? value.length, end: el?.selectionEnd ?? value.length };
  };

  /** Envuelve lo seleccionado (o un texto de ejemplo seleccionado). */
  const wrap = (before: string, after: string, sample: string) => {
    const { start, end } = selection();
    const selected = value.slice(start, end) || sample;
    const next = value.slice(0, start) + before + selected + after + value.slice(end);
    apply(next, start + before.length, start + before.length + selected.length);
  };

  /** Pone un prefijo a cada línea seleccionada (títulos, listas, citas). */
  const prefix = (make: (i: number) => string) => {
    const { start, end } = selection();
    const lineStart = value.lastIndexOf("\n", start - 1) + 1;
    const lineEnd = value.indexOf("\n", end) === -1 ? value.length : value.indexOf("\n", end);
    const lines = value.slice(lineStart, lineEnd).split("\n");
    const replaced = lines.map((l, i) => make(i) + l.replace(/^(#{1,6} |[-*] \[[ x]\] |[-*] |\d+\. |> )/, "")).join("\n");
    apply(value.slice(0, lineStart) + replaced + value.slice(lineEnd), lineStart + replaced.length);
  };

  /** Inserta un bloque en su propia línea. {@code cursor}: dónde queda el cursor dentro del bloque. */
  const block = (text: string, cursor = text.length, selectLength = 0) => {
    const { start, end } = selection();
    const before = value.slice(0, start);
    const lead = before === "" || before.endsWith("\n\n") ? "" : before.endsWith("\n") ? "\n" : "\n\n";
    const next = before + lead + text + "\n" + value.slice(end).replace(/^\n?/, "\n");
    const at = before.length + lead.length + cursor;
    apply(next, at, at + selectLength);
  };

  const codeBlock = (lang: string) => {
    const { start, end } = selection();
    const selected = value.slice(start, end);
    const sample = lang === "json" ? '{\n  "nombre": "Ana",\n  "correo": "ana@empresa.com"\n}' : "código";
    const body = selected || sample;
    block("```" + lang + "\n" + body + "\n```", 4 + lang.length, body.length);
  };

  const table = () => block("| Campo | Tipo | Descripción |\n|---|---|---|\n| nombre | texto | Nombre completo |\n| correo | texto | Único |", 2, 5);

  const insideCode = () => ((value.slice(0, selection().start).match(/```/g) ?? []).length % 2 === 1);

  const onKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    const mod = e.metaKey || e.ctrlKey;
    if (mod && e.key.toLowerCase() === "b") {
      e.preventDefault();
      wrap("**", "**", "texto");
    } else if (mod && e.key.toLowerCase() === "i") {
      e.preventDefault();
      wrap("_", "_", "texto");
    } else if (mod && e.key.toLowerCase() === "e") {
      e.preventDefault();
      wrap("`", "`", "código");
    } else if (mod && e.key.toLowerCase() === "k") {
      e.preventDefault();
      wrap("[", "](https://)", "enlace");
    } else if (e.key === "Tab" && !e.shiftKey && insideCode()) {
      // Dentro de un bloque de código, Tab sangra; fuera, sigue moviendo el foco (accesibilidad).
      e.preventDefault();
      const { start, end } = selection();
      apply(value.slice(0, start) + "  " + value.slice(end), start + 2);
    }
  };

  const onPaste = (e: React.ClipboardEvent<HTMLTextAreaElement>) => {
    const text = e.clipboardData.getData("text/plain");
    if (!text || insideCode()) return;
    const trimmed = text.trim();
    // JSON pegado: bloque de código formateado.
    if ((trimmed.startsWith("{") || trimmed.startsWith("[")) && trimmed.length > 15) {
      try {
        const pretty = JSON.stringify(JSON.parse(trimmed), null, 2);
        e.preventDefault();
        block("```json\n" + pretty + "\n```");
        return;
      } catch {
        /* no es JSON: se pega tal cual */
      }
    }
    // Celdas de una planilla (separadas por tabulador): tabla.
    const rowsText = trimmed.split(/\r?\n/);
    if (rowsText.length >= 2 && rowsText.every((r) => r.includes("\t"))) {
      e.preventDefault();
      const cells = rowsText.map((r) => r.split("\t").map((c) => c.trim().replace(/\|/g, "\\|")));
      const width = Math.max(...cells.map((r) => r.length));
      const line = (r: string[]) => "| " + Array.from({ length: width }, (_, i) => r[i] ?? "").join(" | ") + " |";
      block([line(cells[0]), "|" + " --- |".repeat(width), ...cells.slice(1).map(line)].join("\n"));
    }
  };

  const tools: { label: string; icon: React.ComponentType<{ className?: string }>; run: () => void; key?: string }[] = [
    { label: "Título", icon: Heading2, run: () => prefix(() => "## ") },
    { label: "Subtítulo", icon: Heading3, run: () => prefix(() => "### ") },
    { label: "Negrita", icon: Bold, run: () => wrap("**", "**", "texto"), key: "⌘B" },
    { label: "Cursiva", icon: Italic, run: () => wrap("_", "_", "texto"), key: "⌘I" },
    { label: "Código en línea", icon: Code, run: () => wrap("`", "`", "código"), key: "⌘E" },
    { label: "Lista", icon: List, run: () => prefix(() => "- ") },
    { label: "Lista numerada", icon: ListOrdered, run: () => prefix((i) => `${i + 1}. `) },
    { label: "Lista de tareas", icon: CheckSquare, run: () => prefix(() => "- [ ] ") },
    { label: "Cita", icon: Quote, run: () => prefix(() => "> ") },
    { label: "Tabla", icon: Table, run: table },
    { label: "Enlace", icon: Link2, run: () => wrap("[", "](https://)", "enlace"), key: "⌘K" },
    { label: "Separador", icon: Minus, run: () => block("---") },
  ];

  return (
    <div ref={setBox} className="rounded-md border border-line bg-surface focus-within:border-line-hover">
      <div role="toolbar" aria-label="Formato" className="flex items-center gap-0.5 overflow-x-auto border-b border-line px-1.5 py-1 sm:flex-wrap sm:overflow-visible">
        {mode === "write" &&
          tools.map((t) => (
            <button
              key={t.label}
              type="button"
              title={t.key ? `${t.label} (${t.key})` : t.label}
              aria-label={t.label}
              onClick={t.run}
              className="grid size-7 shrink-0 place-items-center rounded text-fg-muted hover:bg-hover hover:text-fg-strong"
            >
              <t.icon className="size-3.5" aria-hidden />
            </button>
          ))}
        {mode === "write" && (
          <DropdownMenu
            align="start"
            trigger={
              <button type="button" aria-label="Bloque de código" className="inline-flex h-7 items-center gap-1 rounded px-1.5 text-xs whitespace-nowrap text-fg-muted hover:bg-hover hover:text-fg-strong">
                <Braces className="size-3.5" aria-hidden /> <span className="hidden sm:inline">Bloque de código</span>
              </button>
            }
            items={LANGUAGES.map((l) => ({ label: l.label, onSelect: () => codeBlock(l.id) }))}
          />
        )}
        {mode === "write" && templates.length > 0 && (
          <DropdownMenu
            align="start"
            trigger={
              <button type="button" className="inline-flex h-7 items-center gap-1 rounded px-1.5 text-xs whitespace-nowrap text-fg-muted hover:bg-hover hover:text-fg-strong">
                <FileText className="size-3.5" aria-hidden /> Plantilla
              </button>
            }
            items={templates.map((t) => ({
              label: t.label,
              onSelect: () => (value.trim() ? block(t.text) : apply(t.text, 0)),
            }))}
          />
        )}
        <span className="ml-auto flex shrink-0 items-center gap-0.5" role="tablist" aria-label="Vista">
          {(["write", "preview"] as const).map((m) => (
            <button
              key={m}
              type="button"
              role="tab"
              aria-selected={mode === m}
              onClick={() => setMode(m)}
              className={clsx("h-7 rounded px-2 text-xs", mode === m ? "bg-hover font-medium text-fg-strong" : "text-fg-muted hover:text-fg-strong")}
            >
              {m === "write" ? "Escribir" : "Vista previa"}
            </button>
          ))}
        </span>
      </div>
      {mode === "write" ? (
        <TextArea
          id={id}
          rows={rows}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          onKeyDown={onKeyDown}
          onPaste={onPaste}
          placeholder={placeholder}
          className="!rounded-none !border-0 font-mono text-[13px] leading-relaxed !shadow-none focus:!ring-0"
        />
      ) : (
        <div className="max-h-[32rem] min-h-24 overflow-y-auto px-3.5 py-3">
          {value.trim() ? <Markdown content={value} /> : <p className="text-[13px] text-fg-faint">Nada para mostrar todavía.</p>}
        </div>
      )}
      {hint && mode === "write" && <p className="border-t border-line px-3.5 py-2 text-xs text-fg-faint">{hint}</p>}
    </div>
  );
}
