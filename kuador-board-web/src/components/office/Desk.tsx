"use client";

import { clsx } from "clsx";

import type { Seat } from "./Office";

/**
 * Estado de un puesto: trabajando (teclea, la tarjeta en pantalla), preguntó (globo ❓), error (humo), por aprobar
 * (sello), con tarjetas asignadas, esperando /pendientes (agente local) o libre (toma café).
 */
export type DeskState = "working" | "asking" | "error" | "review" | "busy" | "queued" | "idle";

const STATE_LABEL: Record<DeskState, string> = {
  working: "trabajando",
  asking: "esperando tu respuesta",
  error: "no pudo terminar",
  review: "con tarjetas por aprobar",
  busy: "con tarjetas asignadas",
  queued: "esperando /pendientes",
  idle: "libre",
};

const SKIN = ["#f2c7a5", "#e0ac84", "#c68b5e", "#9c6644", "#6f4a32", "#f6d9c3"];
const HAIR = ["#2b2118", "#5a3a22", "#a0662f", "#1b1b1b", "#c9a15a", "#7a2e1f"];
const DECOR = ["plant", "lamp", "books", "cactus", "frame", "none"] as const;

/** Un número estable por miembro: sus rasgos y la decoración del puesto no cambian entre recargas. */
function hash(s: string): number {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 16777619);
  return Math.abs(h);
}

/** Un escritorio con su persona o agente. Dibujo propio en SVG; las animaciones son CSS (se pausan y respetan «reducir movimiento»). */
export function Desk({ seat, me, roleName, onOpen }: { seat: Seat; me: boolean; roleName: string; onOpen: () => void }) {
  const { member, state, current } = seat;
  const h = hash(member.id);
  const agent = member.kind === "AGENT";
  const decor = DECOR[h % DECOR.length];
  const color = member.avatarColor;
  const screenOn = state === "working" || state === "error";

  return (
    <button
      type="button"
      onClick={onOpen}
      aria-label={`${member.name}, ${roleName}: ${STATE_LABEL[state]}${current ? `, tarjeta ${current.key}` : ""}`}
      className={clsx("office-desk group relative flex w-full flex-col items-center rounded-xl p-1.5 text-center", `office-${state}`, me && "office-me")}
    >
      {(state === "asking" || state === "error" || state === "review") && (
        <span aria-hidden className={clsx("office-bubble", state === "error" && "office-bubble-error")}>
          {state === "asking" ? "❓" : state === "error" ? "⚠️" : `🖋️ ${seat.approvals.length}`}
        </span>
      )}
      <svg viewBox="0 0 160 120" className="w-full max-w-[11rem]" aria-hidden>
        {/* Piso y silla */}
        <ellipse cx="80" cy="112" rx="66" ry="7" className="office-shadow" />
        <rect x="52" y="44" width="40" height="44" rx="10" className="office-chair" />

        {/* Persona o agente */}
        <g className="office-body">
          {agent ? (
            <>
              <line x1="72" y1="16" x2="72" y2="9" className="office-antenna" />
              <circle cx="72" cy="8" r="3" fill={color} className="office-led" />
              <rect x="56" y="16" width="32" height="26" rx="8" className="office-robot-head" />
              <rect x="61" y="24" width="22" height="10" rx="5" className="office-visor" />
              <circle cx="67" cy="29" r="2.4" fill={color} className="office-eye" />
              <circle cx="77" cy="29" r="2.4" fill={color} className="office-eye" />
              <rect x="54" y="44" width="36" height="30" rx="9" fill={color} />
              <rect x="64" y="52" width="16" height="8" rx="2" className="office-chest" />
            </>
          ) : (
            <>
              <circle cx="72" cy="28" r="13" fill={SKIN[h % SKIN.length]} />
              <path d={(h >> 3) % 2 ? "M58 27 Q60 11 72 12 Q86 12 86 27 Q80 18 72 19 Q64 18 58 27Z" : "M59 30 Q57 12 72 13 Q87 13 85 30 L85 24 Q78 16 66 19 Q61 22 59 30Z"} fill={HAIR[(h >> 5) % HAIR.length]} />
              <path d="M52 76 Q52 46 72 44 Q92 46 92 76Z" fill={color} />
            </>
          )}
          {/* Brazos sobre el escritorio (teclean cuando trabaja) */}
          <rect x="58" y="70" width="12" height="7" rx="3.5" fill={color} className="office-arm office-arm-left" />
          <rect x="78" y="70" width="12" height="7" rx="3.5" fill={color} className="office-arm office-arm-right" />
        </g>

        {/* Escritorio */}
        <rect x="14" y="76" width="132" height="10" rx="3" className="office-desktop" />
        <rect x="22" y="86" width="6" height="22" className="office-leg" />
        <rect x="132" y="86" width="6" height="22" className="office-leg" />

        {/* Teclado */}
        <rect x="58" y="72" width="34" height="5" rx="1.5" className="office-keyboard" />

        {/* Monitor (o laptop si es un agente local) */}
        {agent && seat.mode === "LOCAL" ? (
          <g>
            <path d="M100 76 L106 52 L142 52 L138 76Z" className="office-monitor" />
            <path d="M103 74 L108 55 L139 55 L135 74Z" className={clsx("office-screen", screenOn && "office-screen-on")} />
            <rect x="96" y="75" width="46" height="3" rx="1" className="office-monitor" />
          </g>
        ) : (
          <g>
            <rect x="102" y="40" width="42" height="30" rx="3" className="office-monitor" />
            <rect x="105" y="43" width="36" height="24" rx="1.5" className={clsx("office-screen", screenOn && "office-screen-on")} />
            <rect x="120" y="70" width="6" height="6" className="office-monitor" />
            {state === "working" && (
              <g className="office-code">
                <rect x="108" y="54" width="20" height="2" rx="1" />
                <rect x="108" y="58" width="28" height="2" rx="1" />
                <rect x="108" y="62" width="14" height="2" rx="1" />
              </g>
            )}
            {current && screenOn && (
              <text x="123" y="51" textAnchor="middle" className="office-screen-text">
                {current.key}
              </text>
            )}
          </g>
        )}

        {/* Taza (humea cuando está libre) */}
        <rect x="30" y="66" width="10" height="10" rx="2" className="office-mug" />
        {state === "idle" && (
          <g className="office-steam">
            <path d="M33 62 q-3 -5 0 -9" />
            <path d="M37 62 q3 -5 0 -9" />
          </g>
        )}

        {/* Decoración propia del puesto */}
        {decor === "plant" && (
          <g>
            <rect x="140" y="64" width="12" height="12" rx="2" className="office-pot" />
            <path d="M146 64 q-8 -14 -4 -22 M146 64 q6 -12 10 -18 M146 64 q0 -14 2 -24" className="office-leaf" />
          </g>
        )}
        {decor === "cactus" && (
          <g>
            <rect x="141" y="66" width="10" height="10" rx="2" className="office-pot" />
            <rect x="143.5" y="48" width="5" height="19" rx="2.5" className="office-cactus" />
            <path d="M144 58 h-4 v-6" className="office-leaf" />
          </g>
        )}
        {decor === "lamp" && (
          <g>
            <path d="M18 76 L24 50 L34 46" className="office-lamp-arm" />
            <path d="M30 42 L40 46 L34 52Z" className="office-lamp" />
          </g>
        )}
        {decor === "books" && (
          <g>
            <rect x="16" y="64" width="5" height="12" className="office-book-a" />
            <rect x="21" y="66" width="5" height="10" className="office-book-b" />
            <rect x="26" y="62" width="4" height="14" className="office-book-c" />
          </g>
        )}
        {decor === "frame" && <rect x="138" y="62" width="12" height="14" rx="1" className="office-frame" />}

        {/* Humo si falló */}
        {state === "error" && (
          <g className="office-smoke">
            <circle cx="118" cy="36" r="4" />
            <circle cx="126" cy="30" r="5" />
            <circle cx="134" cy="22" r="6" />
          </g>
        )}
      </svg>
      <span className="mt-1 flex max-w-full items-center gap-1 truncate text-xs font-medium text-fg-strong">
        {member.name}
        {me && <span className="rounded bg-sol/20 px-1 text-[10px] font-semibold text-sol-text">Tú</span>}
      </span>
      <span className="max-w-full truncate text-[11px] text-fg-faint">{roleName}</span>
      <span className={clsx("mt-0.5 max-w-full truncate text-[11px]", state === "working" ? "text-info" : state === "error" ? "text-danger" : "text-fg-muted")}>
        {seat.note}
      </span>
    </button>
  );
}
