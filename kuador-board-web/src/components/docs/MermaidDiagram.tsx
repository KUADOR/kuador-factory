"use client";

import { useEffect, useId, useState } from "react";

/**
 * Dibuja un bloque ```mermaid (p. ej. el diagrama de la solución que escribe el arquitecto). La librería se carga solo
 * cuando hay un diagrama en la página. Con nivel de seguridad estricto: el texto del diagrama no puede meter scripts ni
 * enlaces. Si no se puede dibujar, queda el código tal cual.
 */
export function MermaidDiagram({ code }: { code: string }) {
  const id = `mmd-${useId().replace(/[^a-zA-Z0-9]/g, "")}`;
  const [svg, setSvg] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let active = true;
    const dark = document.documentElement.classList.contains("app-dark");
    import("mermaid")
      .then(async ({ default: mermaid }) => {
        mermaid.initialize({ startOnLoad: false, securityLevel: "strict", theme: dark ? "dark" : "default", fontFamily: "inherit" });
        const { svg } = await mermaid.render(id, code);
        if (active) setSvg(svg);
      })
      .catch(() => {
        // Mermaid deja un elemento de error en el body cuando falla.
        document.getElementById(`d${id}`)?.remove();
        if (active) setFailed(true);
      });
    return () => {
      active = false;
    };
  }, [id, code]);

  if (svg) {
    return (
      <div
        role="img"
        aria-label="Diagrama"
        className="doc-mermaid my-3 overflow-x-auto rounded-lg border border-line bg-surface p-3 [&_svg]:mx-auto [&_svg]:h-auto [&_svg]:max-w-full"
        dangerouslySetInnerHTML={{ __html: svg }}
      />
    );
  }
  return (
    <div className="my-3">
      <pre>
        <code>{code}</code>
      </pre>
      {failed && <p className="mt-1 text-xs text-fg-faint">No se pudo dibujar este diagrama: revisa su sintaxis Mermaid.</p>}
    </div>
  );
}
