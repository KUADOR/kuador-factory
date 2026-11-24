"use client";

import { useRef, useState } from "react";

import { Avatar } from "@/components/ui/Avatar";
import { TextArea } from "@/components/ui/Field";
import { handleOf, suggestions, typingMention } from "@/lib/mentions";
import type { Member } from "@/lib/types";

type Props = Omit<React.TextareaHTMLAttributes<HTMLTextAreaElement>, "value" | "onChange"> & {
  value: string;
  onValueChange: (value: string) => void;
  members: Member[];
};

/** Caja de texto que, al escribir «@», sugiere personas del equipo y completa su nombre (↑ ↓ y Enter o Tab). */
export function MentionTextArea({ value, onValueChange, members, onKeyDown, ...rest }: Props) {
  const ref = useRef<HTMLTextAreaElement>(null);
  const [typing, setTyping] = useState<{ start: number; query: string } | null>(null);
  const [active, setActive] = useState(0);
  const options = typing ? suggestions(typing.query, members) : [];
  const open = options.length > 0;

  const track = (el: HTMLTextAreaElement) => {
    setTyping(typingMention(el.value, el.selectionStart ?? el.value.length));
    setActive(0);
  };

  const pick = (m: Member) => {
    const el = ref.current;
    if (!el || !typing) return;
    const caret = el.selectionStart ?? value.length;
    const insert = "@" + handleOf(m, members) + " ";
    const next = value.slice(0, typing.start) + insert + value.slice(caret);
    onValueChange(next);
    setTyping(null);
    const at = typing.start + insert.length;
    requestAnimationFrame(() => {
      el.focus();
      el.setSelectionRange(at, at);
    });
  };

  return (
    <div className="relative">
      <TextArea
        {...rest}
        ref={ref}
        value={value}
        onChange={(e) => {
          onValueChange(e.target.value);
          track(e.target);
        }}
        onClick={(e) => track(e.currentTarget)}
        onBlur={() => setTimeout(() => setTyping(null), 150)}
        onKeyDown={(e) => {
          if (open && !e.metaKey && !e.ctrlKey) {
            if (e.key === "ArrowDown" || e.key === "ArrowUp") {
              e.preventDefault();
              setActive((i) => (i + (e.key === "ArrowDown" ? 1 : options.length - 1)) % options.length);
              return;
            }
            if (e.key === "Enter" || e.key === "Tab") {
              e.preventDefault();
              pick(options[Math.min(active, options.length - 1)]);
              return;
            }
            if (e.key === "Escape") {
              e.preventDefault();
              setTyping(null);
              return;
            }
          }
          onKeyDown?.(e);
        }}
      />
      {open && (
        <ul
          role="listbox"
          aria-label="Mencionar a"
          className="absolute bottom-full left-0 z-20 mb-1 w-64 max-w-full overflow-hidden rounded-md border border-line-strong bg-raised py-1 shadow-[var(--k-shadow)]"
        >
          {options.map((m, i) => (
            <li key={m.id} role="option" aria-selected={i === active}>
              <button
                type="button"
                onMouseDown={(e) => {
                  e.preventDefault();
                  pick(m);
                }}
                className={`flex w-full items-center gap-2 px-2.5 py-1.5 text-left text-[13px] ${i === active ? "bg-hover" : ""}`}
              >
                <Avatar member={m} size="xs" />
                <span className="min-w-0 flex-1 truncate text-fg-strong">{m.name}</span>
                <span className="font-mono text-[11px] text-fg-faint">@{handleOf(m, members)}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
