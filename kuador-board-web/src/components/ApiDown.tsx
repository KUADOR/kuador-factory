import { EmptyState } from "@/components/ui/Section";

/** {@code refused}: la API respondió y no lo permite (una regla, no una caída): solo se explica el motivo. */
export function ApiDown({ message, refused = false }: { message: string; refused?: boolean }) {
  return (
    <div className="max-w-xl px-4 py-8 sm:px-6 sm:py-10">
      <EmptyState title={refused ? "Todavía no se puede abrir" : "No se pudo cargar el tablero"}>
        <p>{message}</p>
        {!refused && (
          <p className="mt-2">
            Verifica que <code className="font-mono text-fg">kuador-board-api</code> esté corriendo y que <code className="font-mono text-fg">BOARD_API_URL</code> apunte a ella.
          </p>
        )}
      </EmptyState>
    </div>
  );
}
