import { boardApi } from "@/lib/api";
import { redirectTo } from "@/lib/redirect";

/** El sistema de la empresa devuelve aquí a la persona después de iniciar sesión (OAuth), con el código para cerrar la conexión. */
export async function GET(request: Request) {
  const params = new URL(request.url).searchParams;
  try {
    const denied = params.get("error");
    if (denied) throw new Error(params.get("error_description") || (denied === "access_denied" ? "No se dio el permiso en el sistema." : `El sistema respondió: ${denied}.`));
    const c = await boardApi.finishConnectionLogin(params.get("state") ?? "", params.get("code") ?? "");
    return redirectTo("/connections", { sistema: c.name });
  } catch (e) {
    return redirectTo("/connections", { errorSistema: e instanceof Error ? e.message : "No se pudo iniciar sesión en el sistema." });
  }
}
