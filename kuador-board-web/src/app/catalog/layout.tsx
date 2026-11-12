import type { Metadata } from "next";

import { CatalogTabs } from "@/components/catalog/CatalogTabs";
import { PageHeader } from "@/components/shell/PageHeader";
import { SettingsArea } from "@/components/shell/SettingsArea";

export const metadata: Metadata = { title: "Catálogo" };

/** Catálogo del espacio: viene con valores de fábrica y cada equipo lo adapta. Solo personas lo editan. */
export default function CatalogLayout({ children }: LayoutProps<"/catalog">) {
  return (
    <SettingsArea>
    <div className="flex min-h-full flex-col">
      <PageHeader
        title="Catálogo del espacio"
        description="Flujos, metodologías, roles y herramientas de este espacio."
      >
        <CatalogTabs />
      </PageHeader>
      {children}
    </div>
    </SettingsArea>
  );
}
