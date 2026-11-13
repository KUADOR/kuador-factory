"use client";

import { createContext, useContext } from "react";

import { canAdminister, canEdit, isOwner } from "@/lib/access";
import type { Me } from "@/lib/types";

type Access = { me: Me | null; canEdit: boolean; canAdminister: boolean; isOwner: boolean };

const AccessContext = createContext<Access>({ me: null, canEdit: false, canAdminister: false, isOwner: false });

/** Quién mira la app; lo provee el armazón para que cada control decida si se muestra. */
export function AccessProvider({ me, children }: { me: Me; children: React.ReactNode }) {
  return <AccessContext value={{ me, canEdit: canEdit(me), canAdminister: canAdminister(me), isOwner: isOwner(me) }}>{children}</AccessContext>;
}

export function useAccess() {
  return useContext(AccessContext);
}
