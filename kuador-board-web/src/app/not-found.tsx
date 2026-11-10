import { Button } from "@/components/ui/Button";
import { EmptyState } from "@/components/ui/Section";

export default function NotFound() {
  return (
    <div className="max-w-xl px-6 py-10">
      <EmptyState title="No encontrado" action={<Button href="/" size="sm">Volver a proyectos</Button>}>
        El proyecto o la página que buscas no existe en este espacio de trabajo.
      </EmptyState>
    </div>
  );
}
