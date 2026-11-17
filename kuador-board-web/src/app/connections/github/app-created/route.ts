import { boardApi } from "@/lib/api";
import { redirectTo } from "@/lib/redirect";

/** GitHub vuelve aquí después de crear la app con el manifiesto: el tablero guarda sus credenciales. */
export async function GET(request: Request) {
  const params = new URL(request.url).searchParams;
  try {
    await boardApi.githubCompleteApp(params.get("code") ?? "", params.get("state") ?? "");
    return redirectTo("/connections", { listo: "app" });
  } catch (e) {
    return redirectTo("/connections", { error: e instanceof Error ? e.message : "No se pudo registrar la app." });
  }
}
