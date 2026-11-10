import type { Me, MemberAccess } from "./types";

/**
 * Quién puede escribir. Es la misma regla que aplica la API (ROLE_EDITOR): la web solo esconde lo
 * que de todos modos sería rechazado. Quien solo mira puede cerrar las puertas donde su rol aprueba.
 */
export function canEdit(me: Pick<Me, "access"> | null | undefined): boolean {
  return !!me && me.access !== "VIEW";
}

export const ACCESS_LABEL: Record<MemberAccess, string> = { EDIT: "Puede editar", VIEW: "Solo lectura" };

/** Por defecto un cliente solo mira (igual que la API). */
export function defaultAccessFor(role: string): MemberAccess {
  return role === "CLIENTE" || role === "CLIENT" ? "VIEW" : "EDIT";
}
