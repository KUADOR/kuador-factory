"use client";

import { Plus } from "lucide-react";
import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";

import { createDocAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import type { DocumentKind } from "@/lib/types";

/** Crea el documento desde su plantilla y abre el editor. */
export function CreateDocButton({
  projectKey,
  kind,
  issueKey = null,
  label,
  compact = false,
}: {
  projectKey: string;
  kind: DocumentKind;
  issueKey?: string | null;
  label: string;
  compact?: boolean;
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
            if (result.ok) router.push(`/projects/${projectKey}/docs/${result.data.document.id}`);
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
