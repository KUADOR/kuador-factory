import { notFound } from "next/navigation";

import { SectionLayout, SectionNav } from "@/components/shell/SectionNav";
import { boardApi } from "@/lib/api";

/** Plataforma: solo para quien administra Kuador Go. Una sección a la vez, con submenú. */
export default async function PlatformLayout({ children }: { children: React.ReactNode }) {
  const me = await boardApi.me().catch(() => null);
  if (!me?.platformAdmin) notFound();
  return (
    <SectionLayout
      title="Plataforma"
      nav={
        <SectionNav
          label="Secciones de la plataforma"
          groups={[
            {
              title: "Clientes",
              items: [
                { href: "/platform", label: "Espacios y licencias", exact: true },
                { href: "/platform/audit", label: "Auditoría" },
              ],
            },
            {
              title: "Venta",
              items: [
                { href: "/platform/plans", label: "Planes y avisos" },
                { href: "/platform/payments", label: "Pasarelas de pago" },
              ],
            },
            {
              title: "Producto",
              items: [
                { href: "/platform/ai", label: "Inteligencia artificial" },
                { href: "/platform/packages", label: "Paquetes por industria" },
                { href: "/platform/catalog", label: "Catálogo" },
              ],
            },
          ]}
        />
      }
    >
      {children}
    </SectionLayout>
  );
}
