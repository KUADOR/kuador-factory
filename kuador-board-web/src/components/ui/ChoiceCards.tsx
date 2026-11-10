"use client";

import { clsx } from "clsx";

export interface Choice<V extends string> {
  value: V;
  label: string;
  hint?: React.ReactNode;
  badge?: React.ReactNode;
}

/** Elección entre pocas opciones que necesitan explicación (plantilla, metodología, tipo de repositorio). */
export function ChoiceCards<V extends string>({
  name,
  label,
  choices,
  value,
  onChange,
  columns = 3,
}: {
  name: string;
  label: string;
  choices: Choice<V>[];
  value: V | null;
  onChange: (value: V) => void;
  columns?: 2 | 3 | 4;
}) {
  return (
    <div role="radiogroup" aria-label={label} className={clsx("grid gap-2", { 2: "sm:grid-cols-2", 3: "sm:grid-cols-3", 4: "sm:grid-cols-2 lg:grid-cols-4" }[columns])}>
      {choices.map((c) => {
        const checked = value === c.value;
        return (
          <label
            key={c.value}
            className={clsx(
              "flex cursor-pointer flex-col gap-1 rounded-md border px-3 py-2.5 transition-colors has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-offset-2 has-[:focus-visible]:outline-sol",
              checked ? "border-sol bg-sol/8" : "border-line-strong hover:border-line-hover hover:bg-hover/50",
            )}
          >
            <span className="flex items-center gap-2 text-[13px] font-medium text-fg-strong">
              <input type="radio" name={name} value={c.value} checked={checked} onChange={() => onChange(c.value)} className="sr-only" />
              <span aria-hidden className={clsx("grid size-3.5 shrink-0 place-items-center rounded-full border", checked ? "border-sol" : "border-line-hover")}>
                {checked && <span className="size-1.5 rounded-full bg-sol" />}
              </span>
              {c.label}
              {c.badge && <span className="ml-auto">{c.badge}</span>}
            </span>
            {c.hint && <span className="pl-5.5 text-xs leading-relaxed text-fg-muted">{c.hint}</span>}
          </label>
        );
      })}
    </div>
  );
}
