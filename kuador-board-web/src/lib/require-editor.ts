import "server-only";

import { redirect } from "next/navigation";

import { canEdit } from "./access";
import { boardApi } from "./api";

/** Para páginas que solo sirven para editar: quien solo mira vuelve a {@code fallback}. */
export async function requireEditor(fallback = "/"): Promise<void> {
  const me = await boardApi.me().catch(() => null);
  if (me && !canEdit(me)) redirect(fallback);
}
