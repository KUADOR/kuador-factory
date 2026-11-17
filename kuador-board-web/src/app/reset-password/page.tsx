import type { Metadata } from "next";

import { ResetFlow } from "./ResetFlow";

export const metadata: Metadata = { title: "Recuperar contraseña" };

export default async function ResetPage({ searchParams }: { searchParams: Promise<{ token?: string }> }) {
  const { token } = await searchParams;
  return (
    <main className="grid min-h-dvh place-items-center bg-page px-4">
      <div className="w-full max-w-sm">
        <div className="mb-6 flex items-center gap-2.5">
          <span aria-hidden className="size-2.5 rounded-full bg-sol" />
          <span className="text-sm font-semibold tracking-tight text-fg-strong">Kuador Go</span>
        </div>
        <ResetFlow token={typeof token === "string" && token ? token : null} />
      </div>
    </main>
  );
}
