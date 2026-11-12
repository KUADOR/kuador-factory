import { boardApi } from "@/lib/api";
import { canEdit } from "@/lib/access";

import { SectionLayout, SectionNav, type SectionNavGroup } from "./SectionNav";

/**
 * Configuración del espacio en un solo lugar: empresa, equipo, plan, conexiones y lo avanzado, con un submenú.
 * Quien solo mira ve únicamente el equipo; lo de software aparece solo si hay tableros de software.
 */
export async function SettingsArea({ children }: { children: React.ReactNode }) {
  const [me, projects] = await Promise.all([boardApi.me().catch(() => null), boardApi.projects().catch(() => [])]);
  const editor = me ? canEdit(me) : false;
  const software = projects.some((p) => p.kind === "SOFTWARE");
  const groups: SectionNavGroup[] = editor
    ? [
        {
          title: "Espacio",
          items: [
            { href: "/workspace", label: "Empresa e idiomas", exact: true },
            { href: "/team", label: "Equipo" },
            { href: "/workspace/plan", label: "Plan" },
          ],
        },
        {
          title: "Integraciones",
          items: [{ href: "/conexiones", label: "Conexiones" }, ...(software ? [{ href: "/agentes-locales", label: "Agentes locales" }] : [])],
        },
        { title: "Seguridad", items: [{ href: "/auditoria", label: "Auditoría" }] },
        { title: "Avanzado", items: [{ href: "/catalog", label: "Catálogo" }] },
      ]
    : [{ title: "Espacio", items: [{ href: "/team", label: "Equipo" }] }];
  return (
    <SectionLayout title="Configuración" nav={<SectionNav label="Configuración del espacio" groups={groups} />}>
      {children}
    </SectionLayout>
  );
}
