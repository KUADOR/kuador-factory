import type { Metadata } from "next";

import { PageHeader } from "@/components/shell/PageHeader";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";
import { requireEditor } from "@/lib/require-editor";

import { CaptureForm } from "./CaptureForm";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Capturar una idea" };

type Props = { searchParams: Promise<{ title?: string; text?: string; url?: string; board?: string }> };

/**
 * Captura rápida: una idea o un pedido en segundos, escrito o dictado, desde cualquier lugar. También es el destino de
 * «Compartir → Kuador» del teléfono (llega con title, text y url).
 */
export default async function CapturePage({ searchParams }: Props) {
  await requireEditor();
  const { title, text, url, board } = await searchParams;
  let projects;
  try {
    projects = await boardApi.projects();
  } catch (e) {
    return failPage(e);
  }
  const shared = [title, text, url].filter((x) => typeof x === "string" && x.trim()).join("\n").trim();
  return (
    <div className="mx-auto w-full max-w-2xl">
      <PageHeader title="Capturar una idea" />
      <CaptureForm projects={projects} initialText={shared} initialBoard={board ?? null} />
    </div>
  );
}
