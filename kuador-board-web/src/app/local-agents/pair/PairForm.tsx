"use client";

import { Check } from "lucide-react";
import { useState, useTransition } from "react";

import { approveRunnerAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { TextInput } from "@/components/ui/Field";

export function PairForm({ initialCode }: { initialCode: string }) {
  const [code, setCode] = useState(initialCode.toUpperCase());
  const [error, setError] = useState<string | null>(null);
  const [approved, setApproved] = useState<string | null>(null);
  const [pending, start] = useTransition();

  if (approved) {
    return (
      <div className="flex max-w-3xl flex-col gap-2 px-4 py-6 sm:px-6">
        <p className="flex items-center gap-2 text-[15px] font-medium text-fg-strong">
          <Check className="size-5 text-ok" aria-hidden /> «{approved}» quedó conectada
        </p>
        <p className="text-[13px] text-fg-muted">Vuelve a la terminal: sigue con la instalación y te pregunta qué roles atiende.</p>
      </div>
    );
  }
  return (
    <form
      className="flex max-w-md flex-col gap-3 px-4 py-6 sm:px-6"
      onSubmit={(e) => {
        e.preventDefault();
        setError(null);
        start(async () => {
          const r = await approveRunnerAction(code);
          if (r.ok) setApproved(r.data.machineName);
          else setError(r.error);
        });
      }}
    >
      <label htmlFor="code" className="text-[13px] font-medium text-fg-strong">
        Código de la máquina
      </label>
      <TextInput
        id="code"
        value={code}
        onChange={(e) => setCode(e.target.value.toUpperCase())}
        placeholder="ABCD-EFGH"
        autoComplete="off"
        autoCapitalize="characters"
        spellCheck={false}
        className="font-mono text-lg tracking-widest"
        required
      />
      {error && (
        <p role="alert" className="text-[13px] text-danger">
          {error}
        </p>
      )}
      <Button type="submit" tone="primary" icon={Check} loading={pending} disabled={code.replace(/[^A-Z0-9]/g, "").length !== 8} className="self-start">
        Aprobar
      </Button>
    </form>
  );
}
