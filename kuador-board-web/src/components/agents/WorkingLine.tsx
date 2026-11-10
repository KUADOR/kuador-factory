import type { Member } from "@/lib/types";

/** «Agente X trabajando…»: la presencia viva de un agente en un issue (tarjeta y detalle). */
export function WorkingLine({ agent, className }: { agent: Pick<Member, "name">; className?: string }) {
  return (
    <p className={`flex items-center gap-1.5 text-[11px] text-sol-text ${className ?? ""}`} role="status">
      <span aria-hidden className="size-1.5 animate-pulse rounded-full bg-sol motion-reduce:animate-none" />
      {agent.name} trabajando…
    </p>
  );
}
