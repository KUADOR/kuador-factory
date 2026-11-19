import { EmptyState } from "@/components/ui/Section";

export function ApiDown({ message }: { message: string }) {
  return (
    <div className="max-w-xl px-4 py-8 sm:px-6 sm:py-10">
      <EmptyState title="No se pudo cargar el tablero">
        <p>{message}</p>
        <p className="mt-2">
          Verifica que <code className="font-mono text-fg">kuador-board-api</code> esté corriendo y que <code className="font-mono text-fg">BOARD_API_URL</code> apunte a ella.
        </p>
      </EmptyState>
    </div>
  );
}
