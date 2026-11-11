import { boardApi } from "@/lib/api";
import { redirectTo } from "@/lib/redirect";

/** GitHub vuelve aquí después de instalar la app en una organización (con la autorización de quien la instaló). */
export async function GET(request: Request) {
  const params = new URL(request.url).searchParams;
  const installationId = Number(params.get("installation_id"));
  try {
    if (!Number.isFinite(installationId) || installationId <= 0) throw new Error("GitHub no indicó la instalación.");
    const c = await boardApi.githubCompleteInstall(installationId, params.get("code") ?? "", params.get("state") ?? "");
    return redirectTo("/conexiones", { listo: c.account });
  } catch (e) {
    return redirectTo("/conexiones", { error: e instanceof Error ? e.message : "No se pudo conectar la organización." });
  }
}
