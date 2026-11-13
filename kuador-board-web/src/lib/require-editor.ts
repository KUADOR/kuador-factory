import "server-only";

import { redirect } from "next/navigation";

import { canAdminister, canEdit, isOwner } from "./access";
import { boardApi } from "./api";

/** Para páginas que solo sirven para editar: quien solo mira vuelve a {@code fallback}. */
export async function requireEditor(fallback = "/"): Promise<void> {
  const me = await boardApi.me().catch(() => null);
  if (me && !canEdit(me)) redirect(fallback);
}

/** Para páginas de administración del espacio: quien no administra vuelve a {@code fallback}. */
export async function requireAdmin(fallback = "/"): Promise<void> {
  const me = await boardApi.me().catch(() => null);
  if (me && !canAdminister(me)) redirect(fallback);
}

/** Para el plan y los pagos: solo el dueño. */
export async function requireOwner(fallback = "/"): Promise<void> {
  const me = await boardApi.me().catch(() => null);
  if (me && !isOwner(me)) redirect(fallback);
}
