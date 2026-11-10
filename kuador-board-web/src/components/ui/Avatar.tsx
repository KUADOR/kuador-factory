import { Sparkle } from "lucide-react";
import { clsx } from "clsx";

import { initials } from "@/lib/labels";
import type { Member } from "@/lib/types";

type AvatarMember = Pick<Member, "name" | "kind" | "avatarColor">;

const SIZE = {
  xs: "size-5 text-[9px]",
  sm: "size-6 text-[10px]",
  md: "size-8 text-xs",
  lg: "size-10 text-sm",
} as const;

/**
 * Personas y agentes se distinguen por la forma, no solo por un ícono: la persona es un círculo;
 * el agente, un cuadrado redondeado con una chispa. Así se reconoce de un vistazo quién hizo qué.
 */
export function Avatar({ member, size = "sm", className }: { member: AvatarMember | undefined | null; size?: keyof typeof SIZE; className?: string }) {
  if (!member) {
    return (
      <span
        title="Sin asignar"
        className={clsx(SIZE[size], "inline-grid shrink-0 place-items-center rounded-full border border-dashed border-line-hover text-fg-faint", className)}
      >
        <span className="sr-only">Sin asignar</span>
      </span>
    );
  }
  const agent = member.kind === "AGENT";
  // El sol es de la acción y la selección: un avatar guardado con ese color (miembros creados antes
  // de esta regla) se muestra con el azul Pacífico, sin tocar el dato del miembro.
  const color = member.avatarColor.toLowerCase() === "#ffb81c" ? "#8fb4ff" : member.avatarColor;
  return (
    <span title={agent ? `${member.name} (agente)` : member.name} className={clsx("relative inline-flex shrink-0", className)}>
      <span
        className={clsx(SIZE[size], "inline-grid place-items-center font-semibold text-[#0b1017]", agent ? "rounded-[28%]" : "rounded-full")}
        style={{ backgroundColor: color }}
      >
        {initials(member.name)}
      </span>
      {agent && (
        <span className="absolute -top-1 -right-1 grid size-3 place-items-center rounded-full bg-page">
          <Sparkle className="size-2.5 fill-sol text-sol" aria-hidden />
        </span>
      )}
    </span>
  );
}
