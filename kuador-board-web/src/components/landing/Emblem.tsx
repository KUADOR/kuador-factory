import { useId } from "react";

/** Emblema de Kuador: hexágono partido cruzado por la banda del ecuador (latitud 0°). Decorativo. */
export function Emblem({ size = 40, className }: { size?: number; className?: string }) {
  const id = useId();
  const small = size < 24;
  return (
    <svg viewBox="0 0 100 100" width={size} height={size} aria-hidden="true" focusable="false" className={className ?? "block shrink-0 overflow-visible"}>
      <defs>
        <linearGradient id={`${id}-l`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor={small ? "#94a0ae" : "#5a6573"} />
          <stop offset="1" stopColor={small ? "#7c8896" : "#39424e"} />
        </linearGradient>
        <linearGradient id={`${id}-r`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor={small ? "#48525e" : "#1b212a"} />
          <stop offset="1" stopColor={small ? "#3a434f" : "#0e1319"} />
        </linearGradient>
        <linearGradient id={`${id}-b`} x1="0" y1="0" x2="1" y2="0">
          <stop offset="0" stopColor="#ffe3a0" />
          <stop offset=".5" stopColor="#ffb81c" />
          <stop offset="1" stopColor="#ff8a4c" />
        </linearGradient>
      </defs>
      <polygon points="50,2 7,26 7,74 50,98" fill={`url(#${id}-l)`} />
      <polygon points="50,2 93,26 93,74 50,98" fill={`url(#${id}-r)`} />
      <path d="M0,50 L50,42.5 L100,50 L50,57.5 Z" fill={`url(#${id}-b)`} />
    </svg>
  );
}

/** Logo del producto: emblema, KUADOR y el nombre del producto separados por una línea fina. */
export function ProductLogo({ product = "Factory" }: { product?: string }) {
  return (
    <span role="img" aria-label={`Kuador ${product}`} className="inline-flex items-center gap-3 leading-none whitespace-nowrap">
      <Emblem size={28} />
      <span className="font-sans text-[15px] font-bold tracking-[0.16em] text-fg-strong uppercase [font-stretch:116%]">Kuador</span>
      <span className="h-5 w-px shrink-0 bg-line-strong" />
      <span className="text-[15px] font-medium text-fg">{product}</span>
    </span>
  );
}
