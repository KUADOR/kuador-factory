import { redirect } from "next/navigation";

/** El pulso del día es una vista de Inicio (los atajos de la app instalada siguen llegando aquí). */
export default function PulsePage() {
  redirect("/?view=pulse");
}
