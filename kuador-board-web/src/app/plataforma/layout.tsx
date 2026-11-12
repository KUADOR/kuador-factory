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
            { title: "Clientes", items: [{ href: "/plataforma", label: "Espacios y licencias", exact: true }] },
            {
              title: "Venta",
              items: [
                { href: "/plataforma/planes", label: "Planes y avisos" },
                { href: "/plataforma/pagos", label: "Pasarelas de pago" },
              ],
            },
            {
              title: "Producto",
              items: [
                { href: "/plataforma/ia", label: "Inteligencia artificial" },
                { href: "/plataforma/paquetes", label: "Paquetes por industria" },
                { href: "/plataforma/catalogo", label: "Catálogo" },
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
