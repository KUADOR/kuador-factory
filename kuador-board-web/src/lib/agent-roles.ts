import type { CatalogEntries } from "./types";

/** Herramientas que cambian el código del repo: el rol que las usa solo trabaja en la PC de una persona (como en el API). */
const CODE_TOOLS = ["Edit", "Write"];

export type Role = { key: string; name: string; description: string; worksOnCode: boolean };

/** Los roles del catálogo como los usa el alta de un agente. */
export function agentRoles(roles: CatalogEntries["roles"][]): Role[] {
  return roles.map((r) => ({ key: r.key, name: r.name, description: r.description, worksOnCode: r.tools.some((t) => CODE_TOOLS.includes(t)) }));
}
