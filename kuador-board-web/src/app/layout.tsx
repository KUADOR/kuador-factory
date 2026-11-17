import type { Metadata, Viewport } from "next";
import { cache } from "react";
import { Archivo, Martian_Mono } from "next/font/google";
import { cookies } from "next/headers";

import { AppShell } from "@/components/shell/AppShell";
import { ServiceWorker } from "@/components/shell/ServiceWorker";
import { Toaster } from "@/components/ui/Toaster";
import { boardApi } from "@/lib/api";
import { getSessionToken } from "@/lib/session";
import type { Me, Project, Workspace, WorkspacePlan } from "@/lib/types";
import { PrimeProvider } from "@/theme/PrimeProvider";
import { brandFrom, brandVars } from "@/theme/brand";
import { THEME_COOKIE, parseTheme } from "@/theme/theme";

import "./globals.css";

// Con el eje de ancho (wdth): los titulares de la presentación usan Archivo semiexpandido, como kuador.com.
const archivo = Archivo({ subsets: ["latin"], variable: "--font-archivo", display: "swap", axes: ["wdth"] });
const martian = Martian_Mono({ subsets: ["latin"], variable: "--font-martian", display: "swap" });



/** Datos del armazón (una sola vez por petición). Sin sesión o con la API caída no hay armazón: cada página decide. */
const shellData = cache(async function shellData(): Promise<{ me: Me; workspace: Workspace | null; projects: Project[]; plan: WorkspacePlan | null; decisions: number } | null> {
  if (!(await getSessionToken())) return null;
  try {
    const [me, workspace, projects, plan, decisions] = await Promise.all([
      boardApi.me(),
      boardApi.workspace().catch(() => null),
      boardApi.projects().catch(() => [] as Project[]),
      // Para la franja de vencimiento; sin planes (instalación propia) o sin permiso, no hay franja.
      boardApi.workspacePlan().catch(() => undefined),
      // Cuántas decisiones esperan a quien mira (el número junto a «Mis decisiones»).
      boardApi.decisions().then((d) => d.length).catch(() => 0),
    ]);
    return { me, workspace, projects, plan: plan ?? null, decisions };
  } catch {
    return null;
  }
});

/** Dentro de un espacio, las pestañas llevan el nombre de la empresa; fuera (login, alta), el del producto. */
export async function generateMetadata(): Promise<Metadata> {
  const name = (await shellData())?.workspace?.name;
  return {
    title: name ? { default: name, template: `%s · ${name}` } : { default: "Kuador Go", template: "%s · Kuador Go" },
    description: "Tablero de trabajo: personas y agentes de IA en el mismo flujo.",
    robots: { index: false, follow: false },
    // App instalable: en iPhone se abre a pantalla completa con su ícono.
    // «black»: en iPhone la app empieza debajo de la hora y la Dynamic Island (con «black-translucent» quedaba debajo).
    appleWebApp: { capable: true, title: "Kuador", statusBarStyle: "black" },
    icons: { apple: "/icons/apple-touch-icon.png" },
  };
}

export const viewport: Viewport = { themeColor: "#0a0e14", viewportFit: "cover" };

export default async function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  const theme = parseTheme((await cookies()).get(THEME_COOKIE)?.value);
  const shell = await shellData();
  // La marca del espacio pinta el acento (botones primarios, selección, foco) en los dos temas.
  const brand = brandFrom(shell?.workspace?.brandColor, shell?.workspace?.brandOnColor);
  return (
    <html lang="es" className={`${archivo.variable} ${martian.variable} ${theme === "dark" ? "app-dark" : ""}`} style={brandVars(brand) as React.CSSProperties}>
      <body>
        <PrimeProvider license={process.env.PRIMEUI_LICENSE} brandColor={brand.color} brandOnColor={brand.on}>
          {shell ? (
            <AppShell {...shell} theme={theme}>
              {children}
            </AppShell>
          ) : (
            children
          )}
          <Toaster />
          <ServiceWorker />
        </PrimeProvider>
      </body>
    </html>
  );
}
