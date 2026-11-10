"use client";

import { Menu } from "@primereact/ui/menu";
import type { LucideIcon } from "lucide-react";
import { clsx } from "clsx";
import { cloneElement, type ReactElement } from "react";

export interface MenuAction {
  label: string;
  icon?: LucideIcon;
  onSelect: () => void;
  danger?: boolean;
  disabled?: boolean;
}

/** Menú de acciones (⋯, usuario). `trigger` es el botón que lo abre. */
export function DropdownMenu({ trigger, items, align = "end" }: { trigger: React.ReactElement; items: (MenuAction | "separator")[]; align?: "start" | "end" }) {
  return (
    <Menu.Root>
      {/* En PrimeReact 11, asChild entrega las props del disparador a una función: se clonan sobre el botón. */}
      <Menu.Trigger asChild>{(_: unknown, attrs: Record<string, unknown>) => cloneElement(trigger as ReactElement<Record<string, unknown>>, attrs)}</Menu.Trigger>
      <Menu.Portal>
        <Menu.Positioner sideOffset={4} align={align}>
          <Menu.Popup className="min-w-48">
            <Menu.List>
              {items.map((item, i) =>
                item === "separator" ? (
                  <Menu.Separator key={`sep-${i}`} />
                ) : (
                  <Menu.Item
                    key={item.label}
                    disabled={item.disabled}
                    onSelect={item.onSelect}
                    className={clsx(item.danger && "text-danger")}
                  >
                    {item.icon && <item.icon className="size-4 shrink-0" aria-hidden />}
                    {item.label}
                  </Menu.Item>
                ),
              )}
            </Menu.List>
          </Menu.Popup>
        </Menu.Positioner>
      </Menu.Portal>
    </Menu.Root>
  );
}
