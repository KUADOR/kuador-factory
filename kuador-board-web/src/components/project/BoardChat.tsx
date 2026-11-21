"use client";

import { MessageCircle, Plus, Send, Trash2, X } from "lucide-react";
import { useEffect, useRef, useState, useTransition } from "react";

import { askBoardChatAction, createIssueAction } from "@/app/actions";
import { Markdown } from "@/components/docs/Markdown";
import { useAccess } from "@/components/shell/AccessContext";
import { Button } from "@/components/ui/Button";
import { TextArea } from "@/components/ui/Field";
import { notify } from "@/components/ui/Toaster";
import type { ChatTurn, SuggestedEpic } from "@/lib/types";

const STARTERS = ["¿Dónde nos quedamos?", "¿Qué épicas nos sugieres para avanzar?"];

/** Lo que se manda como historial: las últimas vueltas, sin las épicas. */
const HISTORY = 6;

function load(key: string): ChatTurn[] {
  try {
    const raw = localStorage.getItem(key);
    return raw ? (JSON.parse(raw) as ChatTurn[]) : [];
  } catch {
    return [];
  }
}

function save(key: string, turns: ChatTurn[]) {
  try {
    localStorage.setItem(key, JSON.stringify(turns.slice(-20)));
  } catch {
    // Sin almacenamiento la conversación dura lo que la pestaña.
  }
}

/**
 * Burbuja del chat del tablero (abajo a la derecha): pregunta en qué va el tablero y qué conviene hacer. Las épicas que
 * sugiere se crean con un clic en la primera columna. La conversación vive en este navegador, una por tablero.
 */
export function BoardChat({
  projectKey,
  firstColumn,
  epics,
}: {
  projectKey: string;
  firstColumn: string;
  epics: boolean;
}) {
  const storageKey = `kuador.chat.${projectKey}`;
  const { canEdit } = useAccess();
  const [open, setOpen] = useState(false);
  const [turns, setTurns] = useState<ChatTurn[]>([]);
  const [draft, setDraft] = useState("");
  const [pending, start] = useTransition();
  const [created, setCreated] = useState<Record<string, string>>({});
  const end = useRef<HTMLDivElement>(null);

  // Con llaves: en Chrome reciente scrollIntoView devuelve una promesa, y un efecto no puede devolver nada más que su limpieza.
  useEffect(() => {
    end.current?.scrollIntoView({ block: "end" });
  }, [turns, pending, open]);

  function ask(text: string) {
    const question = text.trim();
    if (!question || pending) return;
    const history = turns.slice(-HISTORY).map(({ role, text }) => ({ role, text }));
    const asked: ChatTurn[] = [...turns, { role: "user", text: question }];
    setTurns(asked);
    setDraft("");
    start(async () => {
      const r = await askBoardChatAction(projectKey, question, history);
      const next: ChatTurn[] = r.ok
        ? [...asked, { role: "assistant", text: r.data.text, epics: r.data.epics }]
        : [...asked, { role: "assistant", text: `⚠️ ${r.error}` }];
      setTurns(next);
      save(storageKey, next);
    });
  }

  function createEpic(e: SuggestedEpic) {
    start(async () => {
      const r = await createIssueAction(projectKey, {
        title: e.title,
        description: e.description || null,
        type: "EPIC",
        priority: "MEDIUM",
        storyPoints: null,
        assigneeId: null,
        parentKey: null,
        labels: ["sugerida"],
        status: firstColumn,
      });
      if (r.ok) {
        setCreated((c) => ({ ...c, [e.title]: r.data.key }));
        notify.ok(`Épica ${r.data.key} creada`, e.title);
      } else notify.error("No se pudo crear la épica", r.error);
    });
  }

  function clear() {
    setTurns([]);
    save(storageKey, []);
  }

  if (!open) {
    return (
      <button
        type="button"
        onClick={() => {
          setTurns(load(storageKey));
          setOpen(true);
        }}
        aria-label="Abrir el chat del tablero"
        title="¿Dónde nos quedamos?"
        className="fixed right-4 bottom-4 z-40 flex size-12 items-center justify-center rounded-full bg-sol text-on-sol shadow-lg transition hover:brightness-110 focus-visible:outline-2 focus-visible:outline-offset-2"
      >
        <MessageCircle className="size-5" aria-hidden />
      </button>
    );
  }

  return (
    <section
      aria-label="Chat del tablero"
      className="fixed right-4 bottom-4 z-40 flex h-[min(560px,calc(100dvh-2rem))] w-[min(380px,calc(100vw-2rem))] flex-col overflow-hidden rounded-xl border border-line bg-surface shadow-2xl"
    >
      <header className="flex items-center gap-2 border-b border-line px-3 py-2">
        <MessageCircle className="size-4 text-sol-text" aria-hidden />
        <p className="flex-1 text-[13px] font-medium text-fg-strong">Rumbo de {projectKey}</p>
        {turns.length > 0 && (
          <Button
            tone="ghost"
            size="sm"
            icon={Trash2}
            iconOnly
            label="Borrar la conversación"
            onClick={clear}
          />
        )}
        <Button
          tone="ghost"
          size="sm"
          icon={X}
          iconOnly
          label="Cerrar"
          onClick={() => setOpen(false)}
        />
      </header>

      <div className="flex-1 space-y-3 overflow-y-auto px-3 py-3 text-[13px]">
        {turns.length === 0 && (
          <div className="space-y-2">
            <p className="text-fg-muted">
              Pregúntame en qué va el tablero, qué falta o qué conviene abrir.
            </p>
            {STARTERS.map((s) => (
              <button
                key={s}
                type="button"
                onClick={() => ask(s)}
                className="block w-full rounded-lg border border-line px-3 py-2 text-left text-fg hover:border-line-hover"
              >
                {s}
              </button>
            ))}
          </div>
        )}
        {turns.map((t, i) =>
          t.role === "user" ? (
            <p key={i} className="ml-8 rounded-lg bg-sunken px-3 py-2 text-fg">
              {t.text}
            </p>
          ) : (
            <div key={i} className="mr-4 space-y-2">
              <Markdown content={t.text} compact />
              {t.epics?.map((e) => (
                <div key={e.title} className="rounded-lg border border-line px-3 py-2">
                  <p className="font-medium text-fg-strong">{e.title}</p>
                  {e.description && <p className="mt-0.5 text-xs text-fg-muted">{e.description}</p>}
                  {epics && canEdit && (
                    <div className="mt-2">
                      {created[e.title] ? (
                        <span className="text-xs text-ok">Creada: {created[e.title]}</span>
                      ) : (
                        <Button
                          size="sm"
                          icon={Plus}
                          onClick={() => createEpic(e)}
                          disabled={pending}
                        >
                          Crear épica
                        </Button>
                      )}
                    </div>
                  )}
                </div>
              ))}
            </div>
          ),
        )}
        {pending && <p className="text-fg-faint">Mirando el tablero…</p>}
        <div ref={end} />
      </div>

      <form
        className="flex items-end gap-2 border-t border-line p-2"
        onSubmit={(e) => {
          e.preventDefault();
          ask(draft);
        }}
      >
        <TextArea
          rows={1}
          value={draft}
          maxLength={1000}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              ask(draft);
            }
          }}
          placeholder="¿Dónde nos quedamos?"
          aria-label="Tu pregunta"
          className="max-h-28 resize-none text-[13px]"
        />
        <Button
          tone="primary"
          icon={Send}
          iconOnly
          label="Enviar"
          type="submit"
          disabled={pending || !draft.trim()}
        />
      </form>
    </section>
  );
}
