"use client";

import { createContext, useContext } from "react";

import { canEdit } from "@/lib/access";
import type { Me } from "@/lib/types";

const AccessContext = createContext<{ me: Me | null; canEdit: boolean }>({ me: null, canEdit: false });

/** Quién mira la app; lo provee el armazón para que cada control decida si se muestra. */
export function AccessProvider({ me, children }: { me: Me; children: React.ReactNode }) {
  return <AccessContext value={{ me, canEdit: canEdit(me) }}>{children}</AccessContext>;
}

export function useAccess() {
  return useContext(AccessContext);
}
