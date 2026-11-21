import ReactMarkdown, { type Components } from "react-markdown";
import remarkGfm from "remark-gfm";

import { MermaidDiagram } from "./MermaidDiagram";

/** Los bloques ```mermaid se dibujan como diagrama; el resto del código queda como código. */
const components: Components = {
  pre({ children, node, ...rest }) {
    const code = node?.children[0];
    if (code && code.type === "element" && code.tagName === "code") {
      const classes = code.properties.className;
      const lang = Array.isArray(classes) ? classes.map(String) : [];
      const text = code.children.map((c) => (c.type === "text" ? c.value : "")).join("");
      if (lang.includes("language-mermaid")) return <MermaidDiagram code={text.trim()} />;
    }
    return <pre {...rest}>{children}</pre>;
  },
};

/**
 * Muestra Markdown con tablas, listas de tareas y diagramas Mermaid. react-markdown no interpreta HTML incrustado,
 * así que un documento escrito por un agente no puede inyectar scripts.
 */
export function Markdown({ content, compact = false }: { content: string; compact?: boolean }) {
  return (
    <div className={compact ? "doc-prose doc-prose-compact" : "doc-prose"}>
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>
        {content}
      </ReactMarkdown>
    </div>
  );
}
