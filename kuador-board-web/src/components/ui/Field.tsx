"use client";

import { InputText } from "@primereact/ui/inputtext";
import { Textarea } from "@primereact/ui/textarea";
import { Select } from "@primereact/ui/select";
import { ToggleSwitch } from "@primereact/ui/toggleswitch";
import { Check, ChevronDown, Eye, EyeOff } from "lucide-react";
import { clsx } from "clsx";
import { useId, useState } from "react";

/** Etiqueta + control + ayuda o error. El control recibe el id para que la etiqueta lo nombre. */
export function Field({
  label,
  hint,
  error,
  className,
  children,
}: {
  label: string;
  hint?: React.ReactNode;
  error?: string | null;
  className?: string;
  children: (id: string) => React.ReactNode;
}) {
  const id = useId();
  return (
    <div className={clsx("flex min-w-0 flex-col gap-1.5", className)}>
      <label htmlFor={id} className="text-xs font-medium text-fg-muted">
        {label}
      </label>
      {children(id)}
      {error ? (
        <p role="alert" className="text-xs text-danger">
          {error}
        </p>
      ) : (
        hint && <p className="text-xs text-fg-faint">{hint}</p>
      )}
    </div>
  );
}

type TextProps = Omit<React.InputHTMLAttributes<HTMLInputElement>, "size"> & { invalid?: boolean; size?: "sm" | "md"; ref?: React.Ref<HTMLInputElement> };

export function TextInput({ className, invalid, size, ...rest }: TextProps) {
  return <InputText {...rest} invalid={invalid} size={size === "sm" ? "small" : undefined} className={clsx("w-full", className)} />;
}

/** Contraseña o secreto con botón para ver lo escrito (vuelve a ocultarse al enviar el formulario). */
export function PasswordInput({ className, ...rest }: Omit<TextProps, "type">) {
  const [visible, setVisible] = useState(false);
  return (
    <span className="relative block w-full">
      <TextInput {...rest} type={visible ? "text" : "password"} className={clsx("pr-10", className)} />
      <button
        type="button"
        onClick={() => setVisible((v) => !v)}
        aria-label={visible ? "Ocultar lo escrito" : "Mostrar lo escrito"}
        aria-pressed={visible}
        title={visible ? "Ocultar" : "Mostrar"}
        className="absolute top-1/2 right-1 grid size-8 -translate-y-1/2 place-items-center rounded-md text-fg-muted hover:bg-hover hover:text-fg-strong"
      >
        {visible ? <EyeOff className="size-4" aria-hidden /> : <Eye className="size-4" aria-hidden />}
      </button>
    </span>
  );
}

export function TextArea({ className, ...rest }: React.TextareaHTMLAttributes<HTMLTextAreaElement> & { invalid?: boolean; ref?: React.Ref<HTMLTextAreaElement> }) {
  return <Textarea {...rest} className={clsx("w-full", className)} />;
}

export interface SelectOption<V extends string | number> {
  value: V;
  label: string;
  /** Contenido visual opcional (ícono, avatar) a la izquierda de la etiqueta. */
  leading?: React.ReactNode;
}

/** Lista desplegable de PrimeReact con opciones planas. `null` = sin valor (muestra el placeholder). */
export function SelectInput<V extends string | number>({
  id,
  options,
  value,
  onChange,
  placeholder,
  ariaLabel,
  size,
  disabled,
  className,
  filter,
}: {
  id?: string;
  options: SelectOption<V>[];
  value: V | null;
  onChange: (value: V | null) => void;
  placeholder?: string;
  ariaLabel?: string;
  size?: "sm" | "md";
  disabled?: boolean;
  className?: string;
  filter?: boolean;
}) {
  const selected = options.find((o) => o.value === value);
  return (
    <Select.Root
      options={options}
      optionLabel="label"
      optionValue="value"
      value={value}
      disabled={disabled}
      size={size === "sm" ? "small" : undefined}
      onValueChange={(e: { value: unknown }) => onChange((e.value as V | undefined) ?? null)}
      className={clsx("w-full min-w-0", className)}
    >
      <Select.Trigger id={id} aria-label={ariaLabel}>
        <Select.Value placeholder={placeholder}>
          {selected ? (
            <span className="flex min-w-0 items-center gap-2">
              {selected.leading}
              <span className="truncate">{selected.label}</span>
            </span>
          ) : undefined}
        </Select.Value>
        <Select.Indicator>
          <ChevronDown className="size-4" aria-hidden />
        </Select.Indicator>
      </Select.Trigger>
      <Select.Portal>
        <Select.Positioner>
          <Select.Popup>
            {filter && (
              <Select.Header>
                <Select.Filter placeholder="Buscar…" />
              </Select.Header>
            )}
            <Select.List>
              {() =>
                options.map((o, index) => (
                  <Select.Option key={String(o.value)} index={index}>
                    <span className="flex min-w-0 flex-1 items-center gap-2">
                      {o.leading}
                      <span className="truncate">{o.label}</span>
                    </span>
                    <Select.OptionIndicator match="selected">
                      <Check className="size-3.5" aria-hidden />
                    </Select.OptionIndicator>
                  </Select.Option>
                ))
              }
            </Select.List>
            <Select.Empty>Sin resultados</Select.Empty>
          </Select.Popup>
        </Select.Positioner>
      </Select.Portal>
    </Select.Root>
  );
}

export function Switch({
  checked,
  onChange,
  label,
  disabled,
  hideLabel = false,
}: {
  checked: boolean;
  onChange: (checked: boolean) => void;
  label: string;
  disabled?: boolean;
  /** El texto queda solo para lectores de pantalla (cuando ya se lee al lado del interruptor). */
  hideLabel?: boolean;
}) {
  return (
    <label className="inline-flex cursor-pointer items-center gap-2 text-sm text-fg">
      <ToggleSwitch.Root checked={checked} disabled={disabled} onCheckedChange={(e: { checked: boolean }) => onChange(e.checked)} ariaLabel={label}>
        <ToggleSwitch.Control>
          <ToggleSwitch.Handle />
        </ToggleSwitch.Control>
      </ToggleSwitch.Root>
      {!hideLabel && label}
    </label>
  );
}
