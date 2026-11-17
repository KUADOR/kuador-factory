"use client";

import { Toast } from "@primereact/ui/toast";
import { Toaster as PrimeToaster, toast } from "@primereact/ui/toaster";
import { CircleAlert, CircleCheck, Info, TriangleAlert, X } from "lucide-react";

/** Región única de avisos (abajo a la derecha). Se usa con `notify.ok(...)` / `notify.error(...)`. */
export function Toaster() {
  return (
    <PrimeToaster.Root position="top-center">
      <PrimeToaster.Portal>
        <PrimeToaster.Region>
          {({ toaster }: { toaster?: { toasts?: { id: string | number }[] } }) =>
            toaster?.toasts?.map((t) => (
              <Toast.Root key={t.id} toast={t as never}>
                <Toast.Content>
                  <Toast.Icon match="success">
                    <CircleCheck className="size-4" aria-hidden />
                  </Toast.Icon>
                  <Toast.Icon match="error">
                    <CircleAlert className="size-4" aria-hidden />
                  </Toast.Icon>
                  <Toast.Icon match="warn">
                    <TriangleAlert className="size-4" aria-hidden />
                  </Toast.Icon>
                  <Toast.Icon match="info">
                    <Info className="size-4" aria-hidden />
                  </Toast.Icon>
                  <Toast.Message>
                    <Toast.Title />
                    <Toast.Description />
                  </Toast.Message>
                  <Toast.Close aria-label="Cerrar aviso">
                    <X className="size-3.5" aria-hidden />
                  </Toast.Close>
                </Toast.Content>
              </Toast.Root>
            ))
          }
        </PrimeToaster.Region>
      </PrimeToaster.Portal>
    </PrimeToaster.Root>
  );
}

export const notify = {
  ok: (title: string, description?: string) => toast.success({ title, description }),
  error: (title: string, description?: string) => toast.error({ title, description }),
  info: (title: string, description?: string) => toast.info({ title, description }),
};
