import { boardApi } from "@/lib/api";
import { canAdminister, canEdit, isOwner } from "@/lib/access";

import { SectionLayout, SectionNav, type SectionNavGroup } from "./SectionNav";

/**
 * Configuración del espacio en un solo lugar: empresa, equipo, plan, conexiones y lo avanzado, con un submenú.
 * Cada nivel ve lo suyo: quien solo mira, el equipo; quien edita, además sus agentes locales; quien administra,
 * todo salvo el plan, que es del dueño. Lo de software aparece solo si hay tableros de software.
 */
export async function SettingsArea({ children }: { children: React.ReactNode }) {
  const [me, projects] = await Promise.all([boardApi.me().catch(() => null), boardApi.projects().catch(() => [])]);
  const editor = canEdit(me);
  const admin = canAdminister(me);
  const software = projects.some((p) => p.kind === "SOFTWARE");
  const localAgents = editor && software ? [{ href: "/local-agents", label: "Agentes locales" }] : [];
  const groups: SectionNavGroup[] = admin
    ? [
        {
          title: "Espacio",
          items: [
            { href: "/workspace", label: "Empresa e idiomas", exact: true },
            { href: "/team", label: "Equipo" },
            ...(isOwner(me) ? [{ href: "/workspace/plan", label: "Plan" }] : []),
          ],
        },
        { title: "Integraciones", items: [{ href: "/connections", label: "Conexiones" }, ...localAgents] },
        { title: "Seguridad", items: [{ href: "/audit", label: "Auditoría" }] },
        { title: "Avanzado", items: [{ href: "/catalog", label: "Catálogo" }] },
      ]
    : [
        { title: "Espacio", items: [{ href: "/team", label: "Equipo" }] },
        ...(localAgents.length ? [{ title: "Integraciones", items: localAgents }] : []),
      ];
  return (
    <SectionLayout title="Configuración" nav={<SectionNav label="Configuración del espacio" groups={groups} />}>
      {children}
    </SectionLayout>
  );
}
