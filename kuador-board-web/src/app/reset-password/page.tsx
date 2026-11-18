import { KuadorBrand } from "@/components/brand/KuadorLogo";
import type { Metadata } from "next";

import { ResetFlow } from "./ResetFlow";

export const metadata: Metadata = { title: "Recuperar contraseña" };

export default async function ResetPage({
  searchParams,
}: {
  searchParams: Promise<{ token?: string }>;
}) {
  const { token } = await searchParams;
  return (
    <main className="grid min-h-dvh place-items-center bg-page px-4">
      <div className="w-full max-w-sm">
        <div className="mb-6">
          <KuadorBrand />
        </div>
        <ResetFlow token={typeof token === "string" && token ? token : null} />
      </div>
    </main>
  );
}
