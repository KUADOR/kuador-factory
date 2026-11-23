"use client";

import { Plus, Trash } from "lucide-react";

import { Button } from "@/components/ui/Button";
import { SelectInput, TextInput } from "@/components/ui/Field";
import { FIELD_KIND_LABEL } from "@/lib/labels";
import { FIELD_KINDS, type FieldDef } from "@/lib/types";

const MAX = 12;

/** «Fecha de cierre» → fecha_de_cierre (la clave estable del dato). */
function keyFrom(label: string, taken: string[]) {
  const base =
    label
      .normalize("NFD")
      .replace(/[̀-ͯ]/g, "")
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, "_")
      .replace(/^[^a-z]+|_+$/g, "")
      .slice(0, 26) || "dato";
  let key = base;
  for (let n = 2; taken.includes(key); n++) key = `${base}_${n}`;
  return key;
}

/** Los datos propios de un tipo de tarjeta: nombre, tipo y, en las listas, sus opciones. */
export function FieldsEditor({ typeLabel, value, onChange }: { typeLabel: string; value: FieldDef[]; onChange: (fields: FieldDef[]) => void }) {
  const patch = (i: number, change: Partial<FieldDef>) => onChange(value.map((f, j) => (j === i ? { ...f, ...change } : f)));
  return (
    <div className="flex flex-col gap-2">
      {value.map((f, i) => (
        <div key={i} className="grid grid-cols-[minmax(0,1fr)_7rem_auto] items-start gap-2">
          <div className="flex flex-col gap-1.5">
            <TextInput
              size="sm"
              required
              maxLength={40}
              value={f.label}
              aria-label={`Nombre del dato ${i + 1} de ${typeLabel}`}
              placeholder="Monto"
              onChange={(e) => patch(i, { label: e.target.value })}
              // La clave se fija una vez, al terminar de escribir el nombre: renombrarlo después no pierde lo ya cargado en las tarjetas.
              onBlur={() => !f.key && f.label.trim() && patch(i, { key: keyFrom(f.label, value.map((x) => x.key)) })}
            />
            {f.kind === "CHOICE" && (
              <TextInput
                size="sm"
                required
                value={f.options.join(", ")}
                aria-label={`Opciones de ${f.label || `el dato ${i + 1}`}`}
                placeholder="Referido, Web, Llamada"
                onChange={(e) => patch(i, { options: e.target.value.split(",").map((o) => o.trimStart()) })}
              />
            )}
          </div>
          <SelectInput
            size="sm"
            ariaLabel={`Tipo de ${f.label || `el dato ${i + 1}`}`}
            options={FIELD_KINDS.map((k) => ({ value: k, label: FIELD_KIND_LABEL[k] }))}
            value={f.kind}
            onChange={(kind) => kind && patch(i, { kind, options: kind === "CHOICE" ? f.options : [] })}
          />
          <Button tone="ghost" size="sm" icon={Trash} iconOnly label={`Quitar ${f.label || "el dato"}`} onClick={() => onChange(value.filter((_, j) => j !== i))} />
        </div>
      ))}
      {value.length < MAX && (
        <div>
          <Button size="sm" tone="ghost" icon={Plus} onClick={() => onChange([...value, { key: "", label: "", kind: "TEXT", options: [] }])}>
            Agregar dato
          </Button>
        </div>
      )}
    </div>
  );
}

/** Lo que se envía: opciones limpias y una clave para los datos que aún no la tienen. */
export function cleanFields(fields: FieldDef[]): FieldDef[] {
  const taken: string[] = fields.map((f) => f.key).filter(Boolean);
  return fields.map((f) => {
    const key = f.key || keyFrom(f.label, taken);
    if (!f.key) taken.push(key);
    return { key, label: f.label.trim(), kind: f.kind, options: f.kind === "CHOICE" ? f.options.map((o) => o.trim()).filter(Boolean) : [] };
  });
}
