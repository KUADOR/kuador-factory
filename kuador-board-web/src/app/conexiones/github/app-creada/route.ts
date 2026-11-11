import { NextResponse } from "next/server";

import { boardApi } from "@/lib/api";

/** GitHub vuelve aquí después de crear la app con el manifiesto: el tablero guarda sus credenciales. */
export async function GET(request: Request) {
  const params = new URL(request.url).searchParams;
  const back = new URL("/conexiones", request.url);
  try {
    await boardApi.githubCompleteApp(params.get("code") ?? "", params.get("state") ?? "");
    back.searchParams.set("listo", "app");
  } catch (e) {
    back.searchParams.set("error", e instanceof Error ? e.message : "No se pudo registrar la app.");
  }
  return NextResponse.redirect(back);
}
