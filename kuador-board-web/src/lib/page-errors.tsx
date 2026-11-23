import "server-only";

import { notFound, redirect } from "next/navigation";

import { ApiDown } from "@/components/ApiDown";

import { ApiError } from "./api";

/** Traduce un error de la API al cargar una página: 401 → login, 404 → no encontrado, resto → aviso. */
export function failPage(error: unknown): React.ReactElement {
  if (error instanceof ApiError && error.status === 401) redirect("/login");
  // Una ruta que la API todavía no tiene (web más nueva que la API) no es «no encontrado»: se explica.
  if (error instanceof ApiError && error.status === 404 && !error.message.startsWith("Esta función todavía no está")) notFound();
  return <ApiDown message={error instanceof Error ? error.message : String(error)} />;
}
