"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { setLicenseAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { SelectInput, TextInput } from "@/components/ui/Field";
import { Section } from "@/components/ui/Section";
import { notify } from "@/components/ui/Toaster";
import type { LicenseStatus, Plan, WorkspaceLicense } from "@/lib/types";

import { STATUS_LABEL, dateLabel } from "./format";

const STATUSES = (Object.keys(STATUS_LABEL) as LicenseStatus[]).map((s) => ({ value: s, label: STATUS_LABEL[s] }));

/** Licencia de cada espacio. Aquí se registra un pago manual (estado activo y hasta cuándo) o se extiende una prueba. */
export function LicensesTable({ workspaces, plans }: { workspaces: WorkspaceLicense[]; plans: Plan[] }) {
  return (
    <Section title="Espacios y licencias" description="Los espacios sin plan no tienen límites." flush>
      <div className="overflow-x-auto">
        <table className="w-full min-w-[720px] text-sm">
          <thead>
            <tr className="border-b border-line text-left text-[12px] text-fg-muted">
              <th className="px-5 py-2 font-medium">Espacio</th>
              <th className="px-3 py-2 font-medium">Plan</th>
              <th className="px-3 py-2 font-medium">Estado</th>
              <th className="px-3 py-2 font-medium">Vence</th>
              <th className="px-5 py-2" />
            </tr>
          </thead>
          <tbody className="divide-y divide-line">
            {workspaces.map((w) => (
              <LicenseRow key={w.workspaceId} license={w} plans={plans} />
            ))}
          </tbody>
        </table>
      </div>
    </Section>
  );
}

function LicenseRow({ license, plans }: { license: WorkspaceLicense; plans: Plan[] }) {
  const router = useRouter();
  const [editing, setEditing] = useState(false);
  const [plan, setPlan] = useState(license.plan ?? plans[0]?.code ?? "");
  const [status, setStatus] = useState<LicenseStatus>(license.status ?? "ACTIVE");
  const [until, setUntil] = useState(((license.status === "TRIAL" ? license.trialEndsAt : license.paidUntil) ?? "").slice(0, 10));
  const [pending, start] = useTransition();
  const ends = license.status === "TRIAL" ? license.trialEndsAt : license.paidUntil;

  return (
    <tr className="align-middle">
      <td className="px-5 py-2.5">
        <p className="font-medium text-fg-strong">{license.name}</p>
        <p className="font-mono text-[11px] text-fg-faint">{license.slug}</p>
      </td>
      {editing ? (
        <>
          <td className="px-3 py-2.5">
            <SelectInput ariaLabel="Plan" value={plan} onChange={(v) => v && setPlan(v)} options={plans.map((p) => ({ value: p.code, label: p.name }))} />
          </td>
          <td className="px-3 py-2.5">
            <SelectInput ariaLabel="Estado" value={status} onChange={(v) => v && setStatus(v)} options={STATUSES} />
          </td>
          <td className="px-3 py-2.5">
            <TextInput
              aria-label={status === "TRIAL" ? "Fin de la prueba" : "Pagado hasta (vacío = sin vencimiento)"}
              type="date"
              value={until}
              disabled={status !== "TRIAL" && status !== "ACTIVE"}
              onChange={(e) => setUntil(e.target.value)}
            />
          </td>
          <td className="px-5 py-2.5 text-right whitespace-nowrap">
            <Button tone="ghost" onClick={() => setEditing(false)}>Cancelar</Button>
            <Button
              tone="primary"
              loading={pending}
              onClick={() =>
                start(async () => {
                  const date = until ? new Date(`${until}T23:59:59Z`).toISOString() : null;
                  const r = await setLicenseAction(license.workspaceId, {
                    plan,
                    status,
                    trialEndsAt: status === "TRIAL" ? date : null,
                    paidUntil: status === "ACTIVE" ? date : null,
                  });
                  if (!r.ok) {
                    notify.error(r.error);
                    return;
                  }
                  notify.ok(`Licencia de ${license.name} actualizada`);
                  setEditing(false);
                  router.refresh();
                })
              }
            >
              Guardar
            </Button>
          </td>
        </>
      ) : (
        <>
          <td className="px-3 py-2.5 text-fg">{plans.find((p) => p.code === license.plan)?.name ?? license.plan ?? "Sin plan"}</td>
          <td className="px-3 py-2.5 text-fg">{license.status ? STATUS_LABEL[license.status] : "—"}</td>
          <td className="tabular px-3 py-2.5 text-fg-muted">{ends ? dateLabel(ends) : "—"}</td>
          <td className="px-5 py-2.5 text-right">
            <Button tone="ghost" onClick={() => setEditing(true)}>Editar</Button>
          </td>
        </>
      )}
    </tr>
  );
}
