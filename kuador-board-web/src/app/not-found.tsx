import { Button } from "@/components/ui/Button";
import { EmptyState } from "@/components/ui/Section";

export default function NotFound() {
  return (
    <div className="max-w-xl px-4 py-8 sm:px-6 sm:py-10">
      <EmptyState title="No encontrado" action={<Button href="/projects" size="sm">Volver a proyectos</Button>}>
        El tablero o la página que buscas no existe en este espacio de trabajo.
      </EmptyState>
    </div>
  );
}
