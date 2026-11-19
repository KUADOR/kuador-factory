"use client";

import { Check, Cloud, FolderGit2, GitPullRequest, Laptop, LoaderCircle, Pencil, Plus, Sparkles, Stethoscope, Target, Trash2, Wand2 } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useRef, useState } from "react";
import { clsx } from "clsx";

import { draftApproveAction, draftAskAction, draftJobAction } from "@/app/actions";
import { Markdown } from "@/components/docs/Markdown";
import { Button } from "@/components/ui/Button";
import { DictateButton } from "@/components/ui/DictateButton";
import { TextArea, TextInput } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";
import { COMPONENT_KIND_LABEL } from "@/components/catalog/editors";
import { SelectInput } from "@/components/ui/Field";
import type { ComponentKind, DraftInput, DraftJob, DraftPiece, DraftPlan, DraftStep } from "@/lib/types";

type Doc = { kind: string; label: string; content: string };
const QUICK = ["Más corto", "Más concreto", "Agrega ejemplos", "Menos técnico"];
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/**
 * Redactar con IA los documentos base del tablero (o, fuera de software, su objetivo): la idea central, unas pocas
 * preguntas, los borradores con las plantillas de la metodología y el pulido conversando. Trabaja en el servidor (clave de
 * la empresa) o en tu computador (tu runner con tu suscripción), y al aprobar queda todo como versión 1.
 */
export function DraftingAssistant({
  projectKey,
  plan,
  machineOnline,
  origin: initialOrigin,
  returnTo,
  only,
}: {
  projectKey: string;
  plan: DraftPlan;
  machineOnline: boolean;
  origin?: "new" | "existing";
  /** A dónde volver al aprobar (p. ej. al asistente de inicio, para seguir con el repo). */
  returnTo?: string;
  /** Redactar solo ese documento (p. ej. DEPLOYMENT): sin elegir documentos y sin tocar el objetivo del tablero. */
  only?: string;
}) {
  const router = useRouter();
  // Software sin metodología redacta solo el objetivo, como los demás tableros.
  const software = plan.projectKind === "SOFTWARE" && !!plan.methodology;
  // Un proyecto que ya existe se reconoce leyendo su código; uno nuevo nace de la idea.
  const [origin, setOrigin] = useState<"new" | "existing">(initialOrigin ?? (software && plan.repositories.length > 0 && plan.pieces === 0 ? "existing" : "new"));
  const existing = software && origin === "existing" && !only;
  const onlyDoc = only ? plan.documents.find((d) => d.kind === only) : undefined;
  const [pieces, setPieces] = useState<DraftPiece[]>([]);
  const [findings, setFindings] = useState<string[]>([]);
  const [cards, setCards] = useState<{ title: string; description: string; chosen: boolean }[]>([]);
  const [kitPr, setKitPr] = useState(true);
  const [local, setLocal] = useState(!plan.serverReady);
  const [idea, setIdea] = useState("");
  const [kinds, setKinds] = useState(only ? [only] : plan.documents.filter((d) => d.suggested).map((d) => d.kind));
  const [questions, setQuestions] = useState<{ id: string; text: string; options: string[] }[] | null>(null);
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [docs, setDocs] = useState<Doc[] | null>(null);
  const [goal, setGoal] = useState(plan.goal ?? "");
  const [tab, setTab] = useState<string>("GOAL");
  const [editing, setEditing] = useState(false);
  const [instruction, setInstruction] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const cancelled = useRef(false);

  /** Un paso: en el servidor responde ya; en tu computador se espera a que tu runner lo resuelva. */
  const run = async (step: DraftStep, input: DraftInput, label: string): Promise<DraftJob["result"] | null> => {
    setBusy(label);
    cancelled.current = false;
    try {
      const first = await draftAskAction(projectKey, step, input, local);
      if (!first.ok) {
        notify.error("No se pudo", first.error);
        return null;
      }
      let job = first.data;
      const started = Date.now();
      while (job.status === "PENDING" || job.status === "RUNNING") {
        if (cancelled.current) return null;
        await sleep(2000);
        setBusy(`${label} · tu computador está trabajando (${Math.round((Date.now() - started) / 1000)} s)`);
        const next = await draftJobAction(job.id!);
        if (!next.ok) {
          notify.error("No se pudo", next.error);
          return null;
        }
        job = next.data;
      }
      if (job.status === "FAILED") {
        notify.error("La IA no pudo", job.error ?? undefined);
        return null;
      }
      return job.result;
    } finally {
      setBusy(null);
    }
  };

  const askQuestions = async () => {
    const r = await run("questions", { idea, kinds }, "Pensando qué preguntarte");
    if (r) setQuestions(r.questions ?? []);
  };

  const draft = async () => {
    const r = await run("draft", { idea, answers, kinds }, onlyDoc ? `Redactando ${onlyDoc.label}` : software ? "Redactando los documentos" : "Escribiendo el objetivo");
    if (!r) return;
    setDocs(r.documents ?? []);
    if (r.goal) setGoal(r.goal);
    setTab(r.documents?.[0]?.kind ?? "GOAL");
    setEditing(false);
  };

  const recognize = async () => {
    const r = await run("recognize", { idea, kinds }, local ? "Leyendo el proyecto en tu computador" : "Leyendo el proyecto en GitHub");
    if (!r) return;
    setDocs(r.documents ?? []);
    if (r.goal) setGoal(r.goal);
    setPieces(r.pieces ?? []);
    setFindings(r.findings ?? []);
    setCards((r.cards ?? []).map((c) => ({ ...c, chosen: false })));
    setTab((r.pieces?.length ?? 0) > 0 ? "PIECES" : (r.documents?.[0]?.kind ?? "GOAL"));
    setEditing(false);
  };

  const refine = async (text: string) => {
    const current = tab === "GOAL" ? goal : (docs?.find((d) => d.kind === tab)?.content ?? "");
    const r = await run("refine", { idea, kind: tab, content: current, instruction: text }, "Aplicando tu cambio");
    if (!r?.content) return;
    if (tab === "GOAL") setGoal(r.content);
    else setDocs((all) => (all ?? []).map((d) => (d.kind === tab ? { ...d, content: r.content! } : d)));
    setInstruction("");
    notify.ok("Cambio aplicado");
  };

  const approve = async () => {
    setBusy("Guardando");
    const chosen = cards.filter((c) => c.chosen).map(({ title, description }) => ({ title, description }));
    const r = await draftApproveAction(projectKey, {
      documents: (docs ?? []).map((d) => ({ kind: d.kind, content: d.content })),
      goal: only ? null : goal.trim() || null,
      ...(existing ? { pieces: pieces.filter((p) => p.name.trim() && p.stack.trim()), cards: chosen, kitPullRequest: kitPr } : {}),
    });
    setBusy(null);
    if (!r.ok) return notify.error("No se pudo guardar", r.error);
    if (existing) {
      const parts = [`${r.data.saved.length} documentos`, `${r.data.pieces ?? 0} piezas`, `${r.data.cards?.length ?? 0} tarjetas`];
      notify.ok(`Guardados: ${parts.join(", ")} y el objetivo`, r.data.pullRequests?.length ? `${r.data.pullRequests.length} PR del kit para aprobar en GitHub.` : undefined);
      if (kitPr && r.data.kitNote) notify.error("Sin PR del kit de agentes", r.data.kitNote);
      router.push(`/projects/${projectKey}`);
      return;
    }
    notify.ok(software ? `Guardados ${r.data.saved.length} documentos y el objetivo` : "Objetivo guardado");
    router.push(returnTo ?? (software ? `/projects/${projectKey}/docs` : `/projects/${projectKey}/settings/general`));
  };

  const current = tab === "GOAL" ? null : docs?.find((d) => d.kind === tab);
  const stage = docs ? 3 : questions ? 2 : 1;
  const special = tab === "PIECES" || tab === "FINDINGS";
  const setPiece = (i: number, patch: Partial<DraftPiece>) => setPieces((all) => all.map((p, j) => (j === i ? { ...p, ...patch } : p)));

  const documentChoice = software && !only && plan.documents.length > 0 && (
    <fieldset className="flex flex-col gap-1.5">
      <legend className="mb-1 text-xs font-medium text-fg-muted">Qué documentos redactar</legend>
      {plan.documents.map((d) => (
        <label key={d.kind} className="flex items-start gap-2 text-[13px] text-fg">
          <input
            type="checkbox"
            className="mt-0.5"
            checked={kinds.includes(d.kind)}
            onChange={(e) => setKinds((k) => (e.target.checked ? [...k, d.kind] : k.filter((x) => x !== d.kind)))}
          />
          <span>
            <span className="font-medium text-fg-strong">{d.label}</span>
            {!d.suggested && <span className="text-fg-faint"> · opcional</span>}
            {d.purpose && <span className="block text-xs text-fg-muted">{d.purpose}</span>}
          </span>
        </label>
      ))}
    </fieldset>
  );

  return (
    <div className="flex max-w-3xl flex-col gap-5">
      {/* Dónde trabaja la IA: el servidor (clave de la empresa) o tu computador (tu suscripción, sin gastar la clave). */}
      <div className="flex flex-col gap-2 rounded-lg border border-line bg-surface p-4">
        <span className="text-[13px] font-medium text-fg-strong">Dónde trabaja la IA</span>
        <div className="grid gap-2 sm:grid-cols-2" role="radiogroup" aria-label="Dónde trabaja la IA">
          <ModeCard
            active={!local}
            disabled={!plan.serverReady}
            icon={Cloud}
            title="En el servidor"
            hint={plan.serverReady ? "Gasta la clave de IA de la empresa." : "Sin clave de IA: agrégala en Conexiones → IA."}
            onPick={() => setLocal(false)}
          />
          <ModeCard
            active={local}
            icon={Laptop}
            title="En mi computador"
            hint={machineOnline ? "Usa tu suscripción, no la clave." : "Necesita tu runner prendido."}
            onPick={() => setLocal(true)}
          />
        </div>
      </div>

      {/* ¿Nuevo o ya existe? Lo que existe se lee del código; lo nuevo nace de la idea. */}
      {software && !docs && (
        <div className="grid gap-2 sm:grid-cols-2" role="radiogroup" aria-label="El proyecto">
          <ModeCard active={!existing} icon={Sparkles} title="Proyecto nuevo" hint="Parte de tu idea." onPick={() => setOrigin("new")} />
          <ModeCard
            active={existing}
            icon={FolderGit2}
            title="Ya existe"
            hint="El agente lee el repo y arma el tablero."
            onPick={() => setOrigin("existing")}
          />
        </div>
      )}

      {existing && !docs && (
        <section className="flex flex-col gap-3 rounded-lg border border-sol/50 bg-surface p-4">
          <h2 className="text-[14px] font-semibold text-fg-strong">1 · Leer el proyecto</h2>
          {plan.repositories.length === 0 ? (
            <p className="text-[13px] text-fg-muted">
              Primero vincula el repositorio en{" "}
              <Link href={`/projects/${projectKey}/settings/repository`} className="k-link">
                Piezas y repositorio
              </Link>
              .
            </p>
          ) : (
            <>
              <p className="-mt-1 text-xs text-fg-muted">Lee {plan.repositories.map((r) => r).join(", ")} sin cambiar nada. Nada se guarda hasta que apruebes.</p>
              {local && (
                <p className="text-xs text-fg-muted">
                  Lee la carpeta vinculada con <code className="font-mono text-[11px] text-fg">kuador-runner link {projectKey} &lt;carpeta del repo&gt;</code>
                </p>
              )}
              <div className="flex items-start gap-2">
                <TextArea
                  rows={3}
                  value={idea}
                  onChange={(e) => setIdea(e.target.value)}
                  placeholder="Opcional: qué quieres hacer ahora"
                  aria-label="Qué quieres hacer"
                />
                <DictateButton onText={(t) => setIdea((v) => (v ? `${v} ${t}` : t))} />
              </div>
              {documentChoice}
              <Button tone="primary" icon={Stethoscope} loading={busy != null} disabled={kinds.length === 0} onClick={recognize} className="self-start">
                Leer el proyecto
              </Button>
            </>
          )}
        </section>
      )}

      {/* 1. La idea central. */}
      {!existing && (
      <section className={clsx("flex flex-col gap-3 rounded-lg border bg-surface p-4", stage === 1 ? "border-sol/50" : "border-line")}>
        <h2 className="text-[14px] font-semibold text-fg-strong">{only ? "1 · Tu infraestructura" : "1 · La idea central"}</h2>
        <p className="-mt-2 text-xs text-fg-muted">
          {only ? "Dónde vas a desplegar, dominios y servicios que necesitas. develop va a pruebas y main a producción." : "De qué se trata y para quién, en dos o tres frases."}
        </p>
        <div className="flex items-start gap-2">
          <TextArea
            rows={4}
            value={idea}
            onChange={(e) => setIdea(e.target.value)}
            placeholder={
              only
                ? "VPS en Hetzner con Dokploy. Pruebas en pruebas.clinica.com y producción en clinica.com. Necesito Keycloak y Grafana."
                : software
                  ? "Una app para que la clínica agende citas en línea, simple para pacientes mayores, con recordatorios por WhatsApp."
                  : "Publicar 3 veces por semana para que pymes de la ciudad nos conozcan y pidan una demo."
            }
            aria-label={only ? "Tu infraestructura" : "Idea central"}
          />
          <DictateButton onText={(t) => setIdea((v) => (v ? `${v} ${t}` : t))} />
        </div>
        {documentChoice}
        {stage === 1 && (
          <div className="flex flex-wrap gap-2">
            <Button tone="primary" icon={Sparkles} loading={busy != null} disabled={!idea.trim() || (software && kinds.length === 0)} onClick={askQuestions}>
              Siguiente
            </Button>
            <Button tone="ghost" disabled={busy != null || !idea.trim() || (software && kinds.length === 0)} onClick={draft}>
              Saltar las preguntas
            </Button>
          </div>
        )}
      </section>
      )}

      {/* 2. Las preguntas: solo lo que falta, con opciones para tocar. */}
      {questions && (
        <section className={clsx("flex flex-col gap-4 rounded-lg border bg-surface p-4", stage === 2 ? "border-sol/50" : "border-line")}>
          <h2 className="text-[14px] font-semibold text-fg-strong">2 · Unas preguntas</h2>
          {questions.length === 0 && <p className="text-[13px] text-fg-muted">No hace falta preguntar nada.</p>}
          {questions.map((q) => (
            <div key={q.id} className="flex flex-col gap-2">
              <p className="text-[13px] font-medium text-fg-strong">{q.text}</p>
              <div className="flex flex-wrap gap-1.5">
                {q.options.map((o) => (
                  <button
                    key={o}
                    type="button"
                    aria-pressed={answers[q.text] === o}
                    onClick={() => setAnswers((a) => ({ ...a, [q.text]: o }))}
                    className={clsx(
                      "rounded-full border px-3 py-1.5 text-[13px]",
                      answers[q.text] === o ? "border-sol bg-sol/15 font-medium text-fg-strong" : "border-line text-fg-muted hover:bg-hover",
                    )}
                  >
                    {o}
                  </button>
                ))}
              </div>
              <TextInput
                size="sm"
                value={q.options.includes(answers[q.text] ?? "") ? "" : (answers[q.text] ?? "")}
                onChange={(e) => setAnswers((a) => ({ ...a, [q.text]: e.target.value }))}
                placeholder="Otra respuesta…"
                aria-label={`Otra respuesta: ${q.text}`}
              />
            </div>
          ))}
          {stage === 2 && (
            <Button tone="primary" icon={Wand2} loading={busy != null} onClick={draft} className="self-start">
              {onlyDoc ? `Redactar ${onlyDoc.label}` : software ? "Redactar los documentos" : "Proponer el objetivo"}
            </Button>
          )}
        </section>
      )}

      {/* 3. Los borradores: ver, pulir conversando y aprobar. */}
      {docs && (
        <section className="flex flex-col gap-3 rounded-lg border border-sol/50 bg-surface p-4">
          <h2 className="text-[14px] font-semibold text-fg-strong">{existing ? "2 · Revisa lo que encontró" : "3 · Revisa y pule"}</h2>
          <div className="flex gap-1.5 overflow-x-auto" role="tablist" aria-label="Documentos">
            {[
              ...(existing ? [{ key: "PIECES", label: `Piezas (${pieces.length})` }] : []),
              ...docs.map((d) => ({ key: d.kind, label: d.label })),
              ...(only ? [] : [{ key: "GOAL", label: "Objetivo del tablero" }]),
              ...(existing ? [{ key: "FINDINGS", label: `Diagnóstico y tarjetas` }] : []),
            ].map((t) => (
              <button
                key={t.key}
                type="button"
                role="tab"
                aria-selected={tab === t.key}
                onClick={() => {
                  setTab(t.key);
                  setEditing(false);
                }}
                className={clsx(
                  "flex h-8 shrink-0 items-center gap-1.5 rounded-full border px-3 text-[13px] whitespace-nowrap",
                  tab === t.key ? "border-sol/60 bg-sol/15 font-medium text-fg-strong" : "border-line text-fg-muted",
                )}
              >
                {t.key === "GOAL" && <Target className="size-3.5" aria-hidden />}
                {t.label}
              </button>
            ))}
          </div>

          {tab === "PIECES" ? (
            <PiecesEditor pieces={pieces} repositories={plan.repositories} onChange={setPiece} onRemove={(i) => setPieces((all) => all.filter((_, j) => j !== i))}
              onAdd={() => setPieces((all) => [...all, { name: "", kind: "OTHER", repository: plan.repositories[0] ?? "", path: "", stack: "", build: "", test: "", run: "", notes: "" }])}
            />
          ) : tab === "FINDINGS" ? (
            <div className="flex flex-col gap-4">
              <div>
                <p className="mb-1.5 text-[13px] font-medium text-fg-strong">Diagnóstico</p>
                {findings.length === 0 ? (
                  <p className="text-xs text-fg-muted">No encontró nada para señalar.</p>
                ) : (
                  <ul className="flex list-disc flex-col gap-1 pl-5 text-[13px] text-fg">
                    {findings.map((f) => (
                      <li key={f}>{f}</li>
                    ))}
                  </ul>
                )}
              </div>
              <fieldset className="flex flex-col gap-2">
                <legend className="mb-1.5 text-[13px] font-medium text-fg-strong">Tarjetas para empezar</legend>
                {cards.length === 0 && <p className="text-xs text-fg-muted">No propuso tarjetas.</p>}
                {cards.map((c, i) => (
                  <label key={c.title} className={clsx("flex items-start gap-2.5 rounded-md border px-3 py-2.5 text-[13px]", c.chosen ? "border-sol/60 bg-sol/10" : "border-line")}>
                    <input type="checkbox" className="mt-0.5" checked={c.chosen} onChange={(e) => setCards((all) => all.map((x, j) => (j === i ? { ...x, chosen: e.target.checked } : x)))} />
                    <span>
                      <span className="block font-medium text-fg-strong">{c.title}</span>
                      {c.description && <span className="block text-xs text-fg-muted">{c.description}</span>}
                    </span>
                  </label>
                ))}
              </fieldset>
            </div>
          ) : tab === "GOAL" ? (
            <TextArea rows={3} maxLength={1000} value={goal} onChange={(e) => setGoal(e.target.value)} aria-label="Objetivo del tablero" />
          ) : current && editing ? (
            <TextArea
              rows={18}
              value={current.content}
              onChange={(e) => setDocs((all) => (all ?? []).map((d) => (d.kind === tab ? { ...d, content: e.target.value } : d)))}
              className="font-mono text-[13px]"
              aria-label={current.label}
            />
          ) : current ? (
            <div className="max-h-[28rem] overflow-y-auto rounded-md border border-line bg-page px-4 py-3">
              <Markdown content={current.content} />
            </div>
          ) : null}
          {current && (
            <Button size="sm" tone="ghost" icon={Pencil} className="self-start" onClick={() => setEditing((e) => !e)}>
              {editing ? "Ver formateado" : "Editar a mano"}
            </Button>
          )}

          {/* Pulir conversando: un pedido en lenguaje natural o un toque rápido. */}
          {!special && (
          <form
            className="flex flex-col gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              if (instruction.trim()) void refine(instruction.trim());
            }}
          >
            <div className="flex flex-wrap gap-1.5">
              {QUICK.map((q) => (
                <button key={q} type="button" disabled={busy != null} onClick={() => void refine(q)} className="rounded-full border border-line px-3 py-1 text-xs text-fg-muted hover:bg-hover">
                  {q}
                </button>
              ))}
            </div>
            <div className="flex items-start gap-2">
              <TextInput value={instruction} onChange={(e) => setInstruction(e.target.value)} placeholder="Pídele un cambio" aria-label="Cambio" />
              <DictateButton onText={(t) => setInstruction((v) => (v ? `${v} ${t}` : t))} />
              <Button type="submit" loading={busy != null} disabled={!instruction.trim()}>
                Aplicar
              </Button>
            </div>
          </form>
          )}

          {existing && (
            <label className="flex items-start gap-2.5 text-[13px]">
              <input type="checkbox" className="mt-0.5" checked={kitPr} onChange={(e) => setKitPr(e.target.checked)} />
              <span>
                <span className="flex items-center gap-1.5 font-medium text-fg-strong">
                  <GitPullRequest className="size-3.5" aria-hidden /> Proponer el kit de agentes por Pull Request
                </span>
                <span className="block text-xs text-fg-muted">AGENTS.md y la configuración de Claude Code, nunca directo a la rama principal.</span>
              </span>
            </label>
          )}

          <div className="flex flex-wrap items-center gap-2 border-t border-line pt-3">
            <Button tone="primary" icon={Check} loading={busy === "Guardando"} disabled={busy != null} onClick={approve}>
              {software ? "Aprobar y guardar" : "Guardar el objetivo"}
            </Button>
            <Button tone="ghost" disabled={busy != null} onClick={existing ? recognize : draft}>
              {existing ? "Volver a leer" : "Volver a redactar"}
            </Button>
          </div>
        </section>
      )}

      {busy && busy !== "Guardando" && (
        <div role="status" className="sticky bottom-3 flex items-center gap-2 self-center rounded-full border border-line bg-surface px-4 py-2 text-[13px] text-fg shadow-float">
          <LoaderCircle className="size-4 animate-spin text-sol-text" aria-hidden />
          {busy}…
          {local && (
            <button type="button" onClick={() => (cancelled.current = true)} className="ml-2 text-xs text-fg-faint hover:text-fg-strong">
              Dejar de esperar
            </button>
          )}
        </div>
      )}
    </div>
  );
}

function ModeCard({
  active,
  disabled,
  icon: Icon,
  title,
  hint,
  onPick,
}: {
  active: boolean;
  disabled?: boolean;
  icon: typeof Cloud;
  title: string;
  hint: string;
  onPick: () => void;
}) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={active}
      disabled={disabled}
      onClick={onPick}
      className={clsx(
        "flex items-start gap-2.5 rounded-lg border px-3 py-2.5 text-left disabled:opacity-50",
        active ? "border-sol bg-sol/10" : "border-line hover:bg-hover",
      )}
    >
      <Icon className={clsx("mt-0.5 size-4 shrink-0", active ? "text-sol-text" : "text-fg-muted")} aria-hidden />
      <span>
        <span className="block text-[13px] font-medium text-fg-strong">{title}</span>
        <span className="block text-xs text-fg-muted">{hint}</span>
      </span>
    </button>
  );
}

const KIND_OPTIONS = (Object.keys(COMPONENT_KIND_LABEL) as ComponentKind[]).map((k) => ({ value: k, label: COMPONENT_KIND_LABEL[k] }));

/** Las piezas que encontró en el código: se corrigen (stack, comandos, carpeta) antes de aprobar. */
function PiecesEditor({
  pieces,
  repositories,
  onChange,
  onRemove,
  onAdd,
}: {
  pieces: DraftPiece[];
  repositories: string[];
  onChange: (i: number, patch: Partial<DraftPiece>) => void;
  onRemove: (i: number) => void;
  onAdd: () => void;
}) {
  return (
    <div className="flex flex-col gap-3">
      {pieces.length === 0 && <p className="text-xs text-fg-muted">No reconoció piezas: agrégalas a mano.</p>}
      {pieces.map((p, i) => (
        <div key={i} className="flex flex-col gap-2 rounded-md border border-line bg-page p-3">
          <div className="grid gap-2 sm:grid-cols-[10rem_minmax(0,1fr)_auto]">
            <TextInput size="sm" value={p.name} onChange={(e) => onChange(i, { name: e.target.value })} placeholder="api" aria-label="Nombre de la pieza" className="font-mono" />
            <TextInput size="sm" value={p.stack} onChange={(e) => onChange(i, { stack: e.target.value })} placeholder="Java 21 · Spring Boot 3.4" aria-label="Stack" />
            <Button size="sm" tone="ghost" icon={Trash2} onClick={() => onRemove(i)} aria-label={`Quitar ${p.name || "la pieza"}`} />
          </div>
          <div className="grid gap-2 sm:grid-cols-3">
            <SelectInput size="sm" options={KIND_OPTIONS} value={(p.kind || "OTHER") as ComponentKind} onChange={(v) => onChange(i, { kind: v ?? "OTHER" })} ariaLabel="Tipo de pieza" />
            {repositories.length > 1 ? (
              <SelectInput size="sm" options={repositories.map((r) => ({ value: r, label: r }))} value={p.repository || repositories[0]} onChange={(v) => onChange(i, { repository: v ?? "" })} ariaLabel="Repositorio" />
            ) : (
              <TextInput size="sm" value={p.repository} readOnly aria-label="Repositorio" className="font-mono text-fg-muted" />
            )}
            <TextInput size="sm" value={p.path} onChange={(e) => onChange(i, { path: e.target.value })} placeholder="Carpeta (vacía = raíz)" aria-label="Carpeta" className="font-mono" />
          </div>
          <div className="grid gap-2 sm:grid-cols-3">
            <TextInput size="sm" value={p.build} onChange={(e) => onChange(i, { build: e.target.value })} placeholder="Build" aria-label="Comando de build" className="font-mono" />
            <TextInput size="sm" value={p.test} onChange={(e) => onChange(i, { test: e.target.value })} placeholder="Pruebas" aria-label="Comando de pruebas" className="font-mono" />
            <TextInput size="sm" value={p.run} onChange={(e) => onChange(i, { run: e.target.value })} placeholder="Ejecutar" aria-label="Comando para ejecutar" className="font-mono" />
          </div>
          <TextInput size="sm" value={p.notes} onChange={(e) => onChange(i, { notes: e.target.value })} placeholder="Notas para los agentes (convenciones, versiones…)" aria-label="Notas" />
        </div>
      ))}
      <Button size="sm" tone="ghost" icon={Plus} className="self-start" onClick={onAdd}>
        Agregar pieza
      </Button>
    </div>
  );
}
