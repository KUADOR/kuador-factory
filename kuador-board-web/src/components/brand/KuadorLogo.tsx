import { clsx } from "clsx";

/**
 * El logo de Kuador (el mismo de icon.svg en la raíz de PROYECT_KUADOR): la pirámide de dos caras con la franja del
 * sol, sobre su cuadro oscuro. Es la única marca de la app: menú, barra del celular, accesos, favicon e íconos de la
 * app instalada salen de este dibujo.
 */
export function KuadorLogo({ className, title = "Kuador" }: { className?: string; title?: string }) {
  return (
    <svg viewBox="0 0 64 64" role="img" aria-label={title} className={clsx("shrink-0", className)}>
      <rect width="64" height="64" rx="14" fill="#0E1319" />
      <g transform="translate(11 11) scale(.42)">
        <polygon points="50,2 7,26 7,74 50,98" fill="#94A0AE" />
        <polygon points="50,2 93,26 93,74 50,98" fill="#48525E" />
        <path d="M0,50 L50,42.5 L100,50 L50,57.5 Z" fill="#FFB81C" />
      </g>
    </svg>
  );
}

/** Marca con el nombre, para las pantallas de acceso (ingresar, alta, invitación, clave). */
export function KuadorBrand({ name = "Kuador Go" }: { name?: string }) {
  return (
    <span className="flex items-center gap-2.5">
      <KuadorLogo className="size-8" title="" />
      <span className="text-sm font-semibold tracking-tight text-fg-strong">{name}</span>
    </span>
  );
}
