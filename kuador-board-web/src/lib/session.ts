import "server-only";

import { cookies } from "next/headers";

import { SESSION_COOKIE } from "./session-cookie";

export async function getSessionToken(): Promise<string | undefined> {
  return (await cookies()).get(SESSION_COOKIE)?.value;
}

/**
 * La cookie es httpOnly: el JavaScript del navegador no puede leer el token. Solo el servidor de
 * Next la usa para llamar a la API. SameSite=Lax + la verificación de Origin de las Server Actions
 * protegen contra CSRF.
 */
export async function setSessionToken(token: string, expiresAt: string) {
  (await cookies()).set(SESSION_COOKIE, token, {
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "lax",
    path: "/",
    expires: new Date(expiresAt),
  });
}

export async function clearSessionToken() {
  (await cookies()).delete(SESSION_COOKIE);
}

/** Evita redirecciones abiertas: solo rutas internas. */
export function safeNext(next: unknown): string {
  return typeof next === "string" && next.startsWith("/") && !next.startsWith("//") && !next.startsWith("/\\")
    ? next
    : "/";
}
