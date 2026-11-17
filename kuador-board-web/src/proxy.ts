import { NextResponse, type NextRequest } from "next/server";

import { SESSION_COOKIE } from "@/lib/session-cookie";

// Sin cookie de sesión, todo el tablero lleva al login. La validez real del token la decide la API.
export function proxy(request: NextRequest) {
  const path = request.nextUrl.pathname;
  if (request.cookies.has(SESSION_COOKIE)) {
    // Con sesión, la presentación del producto lleva al tablero.
    return path === "/product" ? NextResponse.redirect(new URL("/", request.url)) : NextResponse.next();
  }
  // Sin sesión, la portada es la presentación del producto (la URL sigue siendo la raíz).
  if (path === "/") return NextResponse.rewrite(new URL("/product", request.url));
  if (path === "/product") return NextResponse.next();
  const login = new URL("/login", request.url);
  const next = request.nextUrl.pathname + request.nextUrl.search;
  if (next !== "/") login.searchParams.set("next", next);
  return NextResponse.redirect(login);
}

export const config = {
  // /attachments queda fuera: el proxy almacenaría el cuerpo de la subida; esas rutas ya exigen la sesión ante la API.
  matcher: ["/((?!login|signup|reset-password|invitation|attachments/|_next/|icon.svg|favicon.ico|manifest.webmanifest|sw.js|icons/).*)"],
};
