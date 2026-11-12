"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { saveBillingSettingsAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { Field, TextInput } from "@/components/ui/Field";
import { Section } from "@/components/ui/Section";
import { notify } from "@/components/ui/Toaster";

/** Días antes del vencimiento (de una prueba o de un periodo pagado) en que se avisa a los dueños de cada espacio. */
export function ReminderSettings({ days }: { days: number[] }) {
  const router = useRouter();
  const [value, setValue] = useState(days.join(", "));
  const [pending, start] = useTransition();
  return (
    <Section
      title="Avisos de vencimiento"
      description="Cuántos días antes del vencimiento se avisa a cada empresa, por correo y Telegram."
    >
      <form
        className="flex flex-wrap items-end gap-3"
        onSubmit={(e) => {
          e.preventDefault();
          const parsed = value.split(/[,\s]+/).filter(Boolean).map(Number);
          if (parsed.some((n) => !Number.isInteger(n))) {
            notify.error("Escribe números enteros separados por coma, ej. 7, 3, 1.");
            return;
          }
          start(async () => {
            const r = await saveBillingSettingsAction(parsed);
            if (!r.ok) {
              notify.error("No se pudo guardar", r.error);
              return;
            }
            setValue(r.data.reminderDays.join(", "));
            notify.ok("Avisos guardados");
            router.refresh();
          });
        }}
      >
        <div className="w-64">
          <Field label="Días antes" hint="Entre 1 y 60, hasta 6 valores.">
            {(id) => <TextInput id={id} value={value} onChange={(e) => setValue(e.target.value)} placeholder="7, 3, 1" />}
          </Field>
        </div>
        <Button type="submit" tone="primary" loading={pending}>Guardar</Button>
      </form>
    </Section>
  );
}
