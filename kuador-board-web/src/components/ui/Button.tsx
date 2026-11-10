"use client";

import { Button as PrimeButton } from "@primereact/ui/button";
import type { LucideIcon } from "lucide-react";
import { LoaderCircle } from "lucide-react";
import Link from "next/link";
import { clsx } from "clsx";

/**
 * Botón Kuador sobre PrimeReact. Cuatro tonos, no más: primary (la acción principal de la vista,
 * una por pantalla), secondary, ghost (acciones de barra o fila) y danger.
 */
export type ButtonTone = "primary" | "secondary" | "ghost" | "danger";

type Props = {
  tone?: ButtonTone;
  size?: "sm" | "md";
  icon?: LucideIcon;
  /** Solo ícono: entonces `label` es obligatorio y va como aria-label + tooltip nativo. */
  iconOnly?: boolean;
  label?: string;
  loading?: boolean;
  href?: string;
  className?: string;
  children?: React.ReactNode;
} & Omit<React.ButtonHTMLAttributes<HTMLButtonElement>, "children">;

const TONE: Record<ButtonTone, { severity?: "secondary" | "danger"; variant?: "text" | "outlined" }> = {
  primary: {},
  secondary: { severity: "secondary", variant: "outlined" },
  ghost: { severity: "secondary", variant: "text" },
  danger: { severity: "danger", variant: "outlined" },
};

export function Button({ tone = "secondary", size = "md", icon: Icon, iconOnly, label, loading, href, className, children, disabled, ...rest }: Props) {
  const content = (
    <>
      {loading ? <LoaderCircle className="size-4 animate-spin" aria-hidden /> : Icon && <Icon className="size-4 shrink-0" aria-hidden />}
      {!iconOnly && (children ?? label)}
    </>
  );
  const common = {
    ...TONE[tone],
    size: size === "sm" ? ("small" as const) : undefined,
    iconOnly,
    "aria-label": iconOnly ? label : undefined,
    title: iconOnly ? label : undefined,
    className: clsx("k-button", className),
  };
  if (href) {
    return (
      <PrimeButton as={Link} href={href} {...common}>
        {content}
      </PrimeButton>
    );
  }
  return (
    <PrimeButton type="button" {...common} {...rest} disabled={disabled || loading} aria-busy={loading || undefined}>
      {content}
    </PrimeButton>
  );
}
