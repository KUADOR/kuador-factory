"use client";

import { Drawer } from "@primereact/ui/drawer";
import { X } from "lucide-react";
import { clsx } from "clsx";

/**
 * Panel lateral derecho (detalle de issue, formularios largos). Deja ver el tablero detrás y se
 * cierra con Escape o el botón; no es un modal centrado.
 */
export function SidePanel({
  open,
  onClose,
  title,
  headerExtra,
  width = "wide",
  children,
  footer,
}: {
  open: boolean;
  onClose: () => void;
  title: React.ReactNode;
  headerExtra?: React.ReactNode;
  width?: "narrow" | "wide";
  children: React.ReactNode;
  footer?: React.ReactNode;
}) {
  return (
    <Drawer.Root open={open} onOpenChange={(e: { value?: boolean }) => !e.value && onClose()} position="right">
      <Drawer.Portal>
        <Drawer.Backdrop />
        <Drawer.Popup className={clsx("flex h-full flex-col p-0", width === "wide" ? "w-[min(960px,100vw)]" : "w-[min(480px,100vw)]")}>
          <Drawer.Header className="flex shrink-0 items-center gap-3 border-b border-line px-5 py-3">
            <Drawer.Title className="min-w-0 flex-1 text-sm font-medium text-fg-strong">{title}</Drawer.Title>
            {headerExtra}
            <Drawer.Close className="grid size-8 place-items-center rounded-md text-fg-muted hover:bg-hover hover:text-fg-strong" aria-label="Cerrar">
              <X className="size-4" aria-hidden />
            </Drawer.Close>
          </Drawer.Header>
          <Drawer.Content className="min-h-0 flex-1 overflow-y-auto p-0">{children}</Drawer.Content>
          {footer && <Drawer.Footer className="border-t border-line px-5 py-3">{footer}</Drawer.Footer>}
        </Drawer.Popup>
      </Drawer.Portal>
    </Drawer.Root>
  );
}
