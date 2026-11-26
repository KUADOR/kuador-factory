import Link from "next/link";

/** Los documentos base se trabajan desde un tablero que usa los documentos del proyecto: sin uno, todavía no hay dónde. */
export function NoDocumentsBoard() {
  return (
    <p className="px-4 py-5 text-[13px] text-fg-muted sm:px-6">
      Ningún tablero trabaja este proyecto con sus documentos todavía.{" "}
      <Link href="/org/boards" className="k-link">
        Súmalo a un tablero
      </Link>
      .
    </p>
  );
}
