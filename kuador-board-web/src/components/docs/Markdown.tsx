import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

/**
 * Muestra Markdown con tablas y listas de tareas. react-markdown no interpreta HTML incrustado,
 * así que un documento escrito por un agente no puede inyectar scripts.
 */
export function Markdown({ content, compact = false }: { content: string; compact?: boolean }) {
  return (
    <div className={compact ? "doc-prose doc-prose-compact" : "doc-prose"}>
      <ReactMarkdown remarkPlugins={[remarkGfm]}>{content}</ReactMarkdown>
    </div>
  );
}
