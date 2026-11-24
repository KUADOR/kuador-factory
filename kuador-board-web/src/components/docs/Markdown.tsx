import ReactMarkdown, { type Components } from "react-markdown";
import remarkGfm from "remark-gfm";

import { MermaidDiagram } from "./MermaidDiagram";
import { linkMentions, MENTION_HREF } from "@/lib/mentions";
import type { Member } from "@/lib/types";

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

/** Las menciones («@persona») se ven como una etiqueta; la de quien mira, con el color de la marca. */
function withMentions(meId: string | undefined): Components {
  return {
    ...components,
    // eslint-disable-next-line @typescript-eslint/no-unused-vars -- node no va al <a>
    a({ href, children, node, ...rest }) {
      if (href?.startsWith(MENTION_HREF)) {
        const mine = href.slice(MENTION_HREF.length) === meId;
        return (
          <span className={mine ? "rounded bg-sol/15 px-1 font-medium text-sol-text" : "rounded bg-hover px-1 font-medium text-fg-strong"}>
            {children}
          </span>
        );
      }
      return (
        <a href={href} {...rest}>
          {children}
        </a>
      );
    },
  };
}

/**
 * Muestra Markdown con tablas, listas de tareas y diagramas Mermaid. react-markdown no interpreta HTML incrustado,
 * así que un documento escrito por un agente no puede inyectar scripts. Con {@code mentions}, resalta a las personas
 * nombradas con «@».
 */
export function Markdown({
  content,
  compact = false,
  mentions,
  meId,
}: {
  content: string;
  compact?: boolean;
  mentions?: Member[];
  meId?: string;
}) {
  return (
    <div className={compact ? "doc-prose doc-prose-compact" : "doc-prose"}>
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={mentions ? withMentions(meId) : components}>
        {mentions ? linkMentions(content, mentions) : content}
      </ReactMarkdown>
    </div>
  );
}
