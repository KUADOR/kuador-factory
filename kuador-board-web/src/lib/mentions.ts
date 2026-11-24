import type { Member } from "@/lib/types";

/**
 * Menciones con «@», con la misma regla que la API (notify/application/Mentions.java): el nombre completo sin espacios
 * ni tildes (@diegoacosta) o el primer nombre (@diego) si nadie más lo comparte. Solo personas; el código no cuenta.
 */
const TOKEN = /(?<![\p{L}\p{N}_.@])@([\p{L}\p{N}][\p{L}\p{N}._-]{0,39})/gu;
const CODE = /```[\s\S]*?(```|$)|`[^`\n]*`/g;

export function normalize(s: string): string {
  return s.normalize("NFD").replace(/\p{M}+/gu, "").toLowerCase().replace(/[^a-z0-9]/g, "");
}

const firstName = (name: string) => normalize(name.trim().split(/\s+/)[0] ?? "");

/** Cómo se nombra a cada persona: su primer nombre si es único; si no, el nombre completo. */
export function handles(members: Member[]): Map<string, Member> {
  const people = members.filter((m) => m.kind === "HUMAN");
  const firsts = new Map<string, number>();
  people.forEach((m) => firsts.set(firstName(m.name), (firsts.get(firstName(m.name)) ?? 0) + 1));
  const out = new Map<string, Member>();
  people.forEach((m) => out.set(normalize(m.name), m));
  people.forEach((m) => {
    const f = firstName(m.name);
    if (firsts.get(f) === 1 && !out.has(f)) out.set(f, m);
  });
  return out;
}

/** El texto a escribir para nombrar a una persona. */
export function handleOf(m: Member, members: Member[]): string {
  const f = firstName(m.name);
  const same = members.filter((x) => x.kind === "HUMAN" && firstName(x.name) === f).length;
  return same === 1 ? f : normalize(m.name);
}

function resolve(token: string, known: Map<string, Member>): Member | undefined {
  return known.get(normalize(token.replace(/[._-]+$/, "")));
}

/** Los ids de las personas que nombra el texto. */
export function mentionedIds(text: string, members: Member[]): Set<string> {
  const ids = new Set<string>();
  if (!text.includes("@")) return ids;
  const known = handles(members);
  for (const m of text.replace(CODE, " ").matchAll(TOKEN)) {
    const who = resolve(m[1], known);
    if (who) ids.add(who.id);
  }
  return ids;
}

/** Prefijo de enlace con que el Markdown dibuja una mención. */
export const MENTION_HREF = "#mencion-";

/** Convierte cada mención en un enlace «#mencion-<id>» (fuera del código) para que el Markdown la resalte. */
export function linkMentions(text: string, members: Member[]): string {
  if (!text.includes("@")) return text;
  const known = handles(members);
  let out = "";
  let last = 0;
  for (const block of text.matchAll(CODE)) {
    out += replaceIn(text.slice(last, block.index), known) + block[0];
    last = block.index + block[0].length;
  }
  return out + replaceIn(text.slice(last), known);
}

function replaceIn(part: string, known: Map<string, Member>): string {
  return part.replace(TOKEN, (all, token: string) => {
    const who = resolve(token, known);
    if (!who) return all;
    const trail = token.match(/[._-]+$/)?.[0] ?? "";
    return `[@${who.name}](${MENTION_HREF}${who.id})${trail}`;
  });
}

/** Lo que se está escribiendo tras una «@» justo antes del cursor (null si no se está nombrando a nadie). */
export function typingMention(text: string, caret: number): { start: number; query: string } | null {
  const m = text.slice(0, caret).match(/(?:^|[^\p{L}\p{N}_.@])@([\p{L}\p{N}._-]{0,40})$/u);
  if (!m) return null;
  return { start: caret - m[1].length - 1, query: normalize(m[1]) };
}

/** Personas que coinciden con lo escrito tras la «@». */
export function suggestions(query: string, members: Member[], limit = 5): Member[] {
  return members
    .filter((m) => m.kind === "HUMAN")
    .filter((m) => !query || normalize(m.name).startsWith(query) || m.name.split(/\s+/).some((w) => normalize(w).startsWith(query)))
    .slice(0, limit);
}
