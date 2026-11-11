import type { Metadata } from "next";
import { cache } from "react";
import { Archivo, Martian_Mono } from "next/font/google";
import { cookies } from "next/headers";

import { AppShell } from "@/components/shell/AppShell";
import { Toaster } from "@/components/ui/Toaster";
import { boardApi } from "@/lib/api";
import { getSessionToken } from "@/lib/session";
import type { Me, Project, Workspace } from "@/lib/types";
import { PrimeProvider } from "@/theme/PrimeProvider";
import { brandFrom, brandVars } from "@/theme/brand";
import { THEME_COOKIE, parseTheme } from "@/theme/theme";

import "./globals.css";

// Con el eje de ancho (wdth): los titulares de la presentación usan Archivo semiexpandido, como kuador.com.
const archivo = Archivo({ subsets: ["latin"], variable: "--font-archivo", display: "swap", axes: ["wdth"] });
const martian = Martian_Mono({ subsets: ["latin"], variable: "--font-martian", display: "swap" });



/** Datos del armazón (una sola vez por petición). Sin sesión o con la API caída no hay armazón: cada página decide. */
const shellData = cache(async function shellData(): Promise<{ me: Me; workspace: Workspace | null; projects: Project[] } | null> {
  if (!(await getSessionToken())) return null;
  try {
    const [me, workspace, projects] = await Promise.all([
      boardApi.me(),
      boardApi.workspace().catch(() => null),
      boardApi.projects().catch(() => [] as Project[]),
    ]);
    return { me, workspace, projects };
  } catch {
    return null;
  }
});

/** Dentro de un espacio, las pestañas llevan el nombre de la empresa; fuera (login, alta), el del producto. */
export async function generateMetadata(): Promise<Metadata> {
  const name = (await shellData())?.workspace?.name;
  return {
    title: name ? { default: name, template: `%s · ${name}` } : { default: "Kuador Factory", template: "%s · Kuador Factory" },
    description: "Tablero de trabajo: personas y agentes de IA en el mismo flujo.",
    robots: { index: false, follow: false },
  };
}

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
        </PrimeProvider>
      </body>
    </html>
  );
}
