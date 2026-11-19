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
          <h1 className="mb-6 text-lg font-semibold tracking-tight text-fg-strong">Entrar</h1>
          <LoginForm next={safeNext(typeof next === "string" ? next : undefined)} />
          <Link href="/reset-password" className="k-link mt-4 inline-block text-xs">
            ¿Olvidaste tu contraseña?
          </Link>
        </div>
        <p className="mt-4 px-6 text-[13px] text-fg-muted">
          ¿Tu empresa no usa el tablero?{" "}
          <Link href="/signup" className="k-link">
            Crear espacio de trabajo
          </Link>
        </p>
      </div>
    </main>
  );
}
