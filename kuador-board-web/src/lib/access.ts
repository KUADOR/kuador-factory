import type { Me, MemberAccess } from "./types";

/**
 * Lo que puede hacer cada nivel. Son las mismas reglas que aplica la API (ROLE_EDITOR, ROLE_ADMIN, ROLE_OWNER):
 * la web solo esconde lo que de todos modos sería rechazado. Quien solo mira puede cerrar las puertas donde su
 * rol aprueba.
 */
type Who = Pick<Me, "access"> | null | undefined;

/** Escribe en los tableros. */
export function canEdit(me: Who): boolean {
  return !!me && me.access !== "VIEW";
}

/** Administra el espacio: equipo, invitaciones, catálogo, IA, conexiones, repositorios y auditoría. */
export function canAdminister(me: Who): boolean {
  return !!me && (me.access === "OWNER" || me.access === "ADMIN");
}

/** Dueño del espacio: además, el plan, los pagos y quién administra. */
export function isOwner(me: Who): boolean {
  return !!me && me.access === "OWNER";
}

/** Si {@code actor} puede dar, quitar o gestionar el nivel {@code level} (igual que la API). */
export function canManage(actor: Who, level: MemberAccess): boolean {
  if (!canAdminister(actor)) return false;
  return level === "EDIT" || level === "VIEW" || isOwner(actor);
}

export const ACCESS_LABEL: Record<MemberAccess, string> = {
  OWNER: "Dueño",
  ADMIN: "Administra",
  EDIT: "Puede editar",
  VIEW: "Solo lectura",
};

export const ACCESS_HINT: Record<MemberAccess, string> = {
  OWNER: "Todo, más el plan, los pagos y quién administra.",
  ADMIN: "El equipo, las invitaciones, el catálogo, la IA, las conexiones y la auditoría.",
  EDIT: "Crea, mueve y comenta tarjetas y documentos.",
  VIEW: "Ve el avance. Aprueba solo en los pasos de su rol.",
};

/** Por defecto un cliente solo mira y nadie nace administrador (igual que la API). */
export function defaultAccessFor(role: string): MemberAccess {
  return role === "CLIENTE" || role === "CLIENT" ? "VIEW" : "EDIT";
}
