/** Horas legibles: minutos si es menos de una hora, horas hasta dos días, días después. */
export function hours(h: number | null | undefined): string {
  if (h == null) return "—";
  if (h <= 0) return "0 h";
  if (h < 1) return `${Math.max(1, Math.round(h * 60))} min`;
  if (h < 48) return `${h.toLocaleString("es", { maximumFractionDigits: h < 10 ? 1 : 0 })} h`;
  return `${(h / 24).toLocaleString("es", { maximumFractionDigits: 1 })} d`;
}

export const usd = (n: number | null | undefined) =>
  n == null ? "—" : `USD ${n.toLocaleString("es", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

export const shortDate = (iso: string) => new Date(iso).toLocaleDateString("es", { day: "numeric", month: "short" });

export const ago = (iso: string) => {
  const min = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60000));
  if (min < 1) return "ahora";
  if (min < 60) return `hace ${min} min`;
  const h = Math.round(min / 60);
  return h < 24 ? `hace ${h} h` : `hace ${Math.round(h / 24)} d`;
};
