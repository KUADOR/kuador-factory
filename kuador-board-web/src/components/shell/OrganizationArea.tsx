import { SectionLayout, SectionNav } from "./SectionNav";

/**
 * Organización: cada cosa se arma una vez y en su lugar. Equipos (quién trabaja), Proyectos (sobre qué se trabaja) y
 * Tableros (cómo se trabaja, que une un equipo con sus proyectos). Es de quien administra.
 */
export function OrganizationArea({ children }: { children: React.ReactNode }) {
  return (
    <SectionLayout
      title="Organización"
      nav={<SectionNav label="Organización" pickerLabel="Organización" groups={[{ items: [{ href: "/org/teams", label: "Equipos" }, { href: "/org/projects", label: "Proyectos" }, { href: "/org/boards", label: "Tableros" }] }]} />}
    >
      {children}
    </SectionLayout>
  );
}
