import type { Metadata } from "next";

import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

import { SalesView } from "./SalesView";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Ventas en movimiento" };

/** Los prospectos de un tablero de ventas con su próxima acción: llamar, escribir, anotar la reunión y avanzar. */
export default async function SalesPage({ params }: PageProps<"/projects/[key]/sales">) {
  const { key } = await params;
  let data;
  try {
    data = await Promise.all([boardApi.project(key), boardApi.issues(key)]);
  } catch (e) {
    return failPage(e);
  }
  const [project, issues] = data;
  return <SalesView project={project} issues={issues} />;
}
