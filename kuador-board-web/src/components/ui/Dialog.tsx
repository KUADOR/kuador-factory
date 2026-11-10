"use client";

import { Dialog as PrimeDialog } from "@primereact/ui/dialog";
import { X } from "lucide-react";

import { Button } from "./Button";

/** Diálogo centrado: solo para confirmar algo destructivo o un paso que exige atención exclusiva. */
export function Dialog({
  open,
  onClose,
  title,
  children,
  footer,
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  children: React.ReactNode;
  footer?: React.ReactNode;
}) {
  return (
    <PrimeDialog.Root open={open} onOpenChange={(e: { value?: boolean }) => !e.value && onClose()} modal>
      <PrimeDialog.Portal>
        <PrimeDialog.Backdrop />
        <PrimeDialog.Positioner>
          <PrimeDialog.Popup className="w-[min(480px,calc(100vw-2rem))]">
            <PrimeDialog.Header>
              <PrimeDialog.Title className="text-base font-medium text-fg-strong">{title}</PrimeDialog.Title>
              <PrimeDialog.HeaderActions>
                <PrimeDialog.Close aria-label="Cerrar" className="grid size-8 place-items-center rounded-md text-fg-muted hover:bg-hover hover:text-fg-strong">
                  <X className="size-4" aria-hidden />
                </PrimeDialog.Close>
              </PrimeDialog.HeaderActions>
            </PrimeDialog.Header>
            <PrimeDialog.Content className="text-sm leading-relaxed text-fg">{children}</PrimeDialog.Content>
            {footer && <PrimeDialog.Footer className="flex justify-end gap-2">{footer}</PrimeDialog.Footer>}
          </PrimeDialog.Popup>
        </PrimeDialog.Positioner>
      </PrimeDialog.Portal>
    </PrimeDialog.Root>
  );
}

/** Confirmación de una acción que no se puede deshacer. */
export function ConfirmDialog({
  open,
  onCancel,
  onConfirm,
  title,
  message,
  confirmLabel,
  pending,
}: {
  open: boolean;
  onCancel: () => void;
  onConfirm: () => void;
  title: string;
  message: React.ReactNode;
  confirmLabel: string;
  pending?: boolean;
}) {
  return (
    <Dialog
      open={open}
      onClose={onCancel}
      title={title}
      footer={
        <>
          <Button tone="ghost" onClick={onCancel}>
            Cancelar
          </Button>
          <Button tone="danger" onClick={onConfirm} loading={pending}>
            {confirmLabel}
          </Button>
        </>
      }
    >
      {message}
    </Dialog>
  );
}
