import { KuadorBrand } from "@/components/brand/KuadorLogo";
import type { Metadata } from "next";
import Link from "next/link";

import { safeNext } from "@/lib/session";

import { LoginForm } from "./LoginForm";

export const metadata: Metadata = { title: "Entrar" };

export default async function LoginPage({ searchParams }: PageProps<"/login">) {
  const { next } = await searchParams;
  return (
    <main className="grid min-h-dvh place-items-center bg-page px-4">
      <div className="w-full max-w-sm">
        <div className="mb-6">
          <KuadorBrand />
        </div>
        <div className="rounded-lg border border-line bg-surface p-6 shadow-float">
          <h1 className="text-lg font-semibold tracking-tight text-fg-strong">Entrar</h1>
          <p className="mt-1 mb-6 text-[13px] text-fg-muted">
            Con el correo que te dio tu espacio de trabajo.
          </p>
          <LoginForm next={safeNext(typeof next === "string" ? next : undefined)} />
          <Link href="/reset-password" className="k-link mt-4 inline-block text-xs">
            ¿Olvidaste tu contraseña?
          </Link>
        </div>
        <div className="mt-4 rounded-lg border border-line bg-surface px-6 py-4">
          <p className="text-[13px] font-medium text-fg-strong">
            ¿Tu empresa todavía no usa el tablero?
          </p>
          <p className="mt-0.5 text-xs text-fg-muted">
            Crea su espacio de trabajo con su logo y sus colores. Verificamos tu correo con un
            código.
          </p>
          <Link href="/signup" className="k-link mt-2 inline-block text-[13px]">
            Crear espacio de trabajo
          </Link>
        </div>
      </div>
    </main>
  );
}
