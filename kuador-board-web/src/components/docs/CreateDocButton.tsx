"use client";

import { Plus } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { createDocAction } from "@/app/actions";
import { useAccess } from "@/components/shell/AccessContext";
import { Button } from "@/components/ui/Button";
import type { DocumentKind } from "@/lib/types";

/** Crea el documento desde su plantilla y abre el editor. */
function CreateDocButtonEditor({
  projectKey,
  kind,
  issueKey = null,
  label,
  compact = false,
  docsBase,
}: {
  projectKey: string;
  kind: DocumentKind;
  issueKey?: string | null;
  label: string;
  compact?: boolean;
  /** Dónde se abren los documentos (por defecto, los del tablero). */
  docsBase?: string;
}) {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();
  return (
    <span className="inline-flex flex-col gap-1">
      <Button
        size="sm"
        tone={compact ? "ghost" : "secondary"}
        icon={Plus}
        loading={pending}
        onClick={() =>
          startTransition(async () => {
            const result = await createDocAction(projectKey, kind, issueKey);
            if (result.ok) router.push(`${docsBase ?? `/projects/${projectKey}/docs`}/${result.data.document.id}`);
            else setError(result.error);
          })
        }
      >
        {label}
      </Button>
      {error && <span className="text-xs text-danger">{error}</span>}
    </span>
  );
}

/** Crear un documento; quien solo mira no lo ve. */
export function CreateDocButton(props: React.ComponentProps<typeof CreateDocButtonEditor>) {
  return useAccess().canEdit ? <CreateDocButtonEditor {...props} /> : null;
}
