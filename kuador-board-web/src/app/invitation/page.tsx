import { KuadorBrand } from "@/components/brand/KuadorLogo";
import type { Metadata } from "next";
import Link from "next/link";

import { boardApi } from "@/lib/api";
import type { InvitationPreview } from "@/lib/types";

import { AcceptForm } from "./AcceptForm";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Invitación" };

export default async function InvitationPage({
  searchParams,
}: {
  searchParams: Promise<{ token?: string }>;
}) {
  const { token } = await searchParams;
  let preview: InvitationPreview | null = null;
  let problem: string | null = null;
  if (typeof token === "string" && token) {
    try {
      preview = await boardApi.invitationPreview(token);
    } catch (e) {
      problem = e instanceof Error ? e.message : "No pudimos abrir la invitación.";
    }
  } else problem = "El enlace está incompleto. Ábrelo de nuevo desde el correo.";

  return (
    <main className="grid min-h-dvh place-items-center bg-page px-4">
      <div className="w-full max-w-sm">
        <div className="mb-6">
          <KuadorBrand />
        </div>
        <div className="rounded-lg border border-line bg-surface p-6 shadow-float">
          {preview && token ? (
            <>
              <h1 className="text-lg font-semibold tracking-tight text-balance text-fg-strong">
                Únete a {preview.workspaceName}
              </h1>
              <p className="mt-1 mb-6 text-[13px] text-fg-muted">
                Hola {preview.memberName}. Entrarás con{" "}
                <span className="font-medium text-fg-strong">{preview.email}</span>.
              </p>
              <AcceptForm token={token} />
            </>
          ) : (
            <>
              <h1 className="text-lg font-semibold tracking-tight text-fg-strong">
                Invitación no válida
              </h1>
              <p className="mt-2 text-[13px] text-fg-muted">{problem}</p>
              <Link href="/login" className="k-link mt-4 inline-block text-[13px]">
                Ir a entrar
              </Link>
            </>
          )}
        </div>
      </div>
    </main>
  );
}
