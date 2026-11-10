"use client";

import { Plus, X } from "lucide-react";

import { SelectInput } from "./Field";

/** Lista de valores como chips quitables, más un selector para agregar los que faltan. */
export function ChipPicker({
  value,
  options,
  onChange,
  placeholder,
  ariaLabel,
  empty,
}: {
  value: string[];
  options: { value: string; label: string }[];
  onChange: (value: string[]) => void;
  placeholder: string;
  ariaLabel: string;
  /** Texto cuando no hay ninguno elegido. */
  empty?: string;
}) {
  const label = (v: string) => options.find((o) => o.value === v)?.label ?? v;
  const missing = options.filter((o) => !value.includes(o.value));
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      {value.length === 0 && empty && <span className="text-xs text-fg-faint italic">{empty}</span>}
      {value.map((v) => (
        <span key={v} className="inline-flex items-center gap-1 rounded-md border border-line-strong bg-raised py-0.5 pr-0.5 pl-2 text-xs text-fg-strong">
          {label(v)}
          <button type="button" onClick={() => onChange(value.filter((x) => x !== v))} aria-label={`Quitar ${label(v)}`} className="grid size-5 place-items-center rounded text-fg-muted hover:bg-hover hover:text-fg-strong">
            <X className="size-3" aria-hidden />
          </button>
        </span>
      ))}
      {missing.length > 0 && (
        <div className="w-44">
          <SelectInput
            size="sm"
            ariaLabel={ariaLabel}
            placeholder={placeholder}
            options={missing.map((o) => ({ ...o, leading: <Plus className="size-3.5 text-fg-faint" aria-hidden /> }))}
            value={null}
            onChange={(v) => v && onChange([...value, v])}
          />
        </div>
      )}
    </div>
  );
}
