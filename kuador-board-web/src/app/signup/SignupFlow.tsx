"use client";

import Link from "next/link";
import { ArrowLeft, Check } from "lucide-react";
import { clsx } from "clsx";
import { useState, useTransition } from "react";

import { signupCodeAction, signupCompleteAction, signupSlugAction, signupVerifyAction } from "@/app/actions";
import { Button } from "@/components/ui/Button";
import { Field, TextInput, PasswordInput } from "@/components/ui/Field";
import { BrandFields, type BrandDraft } from "@/components/workspace/BrandFields";
import { BrandPreview } from "@/components/workspace/BrandPreview";
import { DEFAULT_BRAND } from "@/theme/brand";
import { PlanCard } from "@/components/billing/PlanCard";
import { priceLabel } from "@/components/billing/format";
import type { Plan } from "@/lib/types";

const STEPS = ["Plan", "Tu correo", "Código", "Tu empresa", "Tu cuenta"] as const;

/** Onboarding de un espacio nuevo: correo verificado con código, marca de la empresa y la persona dueña. */
export function SignupFlow({ plans, initialPlan }: { plans: Plan[]; initialPlan: string | null }) {
  // Sin planes a la venta se salta el primer paso.
  const [step, setStep] = useState(plans.length > 0 ? 0 : 1);
  const [planCode, setPlanCode] = useState<string | null>(initialPlan);
  const plan = plans.find((p) => p.code === planCode) ?? null;
  const steps = plans.length > 0 ? STEPS : STEPS.slice(1);
  const shown = plans.length > 0 ? step : step - 1;
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [token, setToken] = useState("");
  const [brand, setBrand] = useState<BrandDraft>({ name: "", description: "", brandColor: DEFAULT_BRAND, brandOnColor: null, logo: null });
  const [slug, setSlug] = useState("");
  const [owner, setOwner] = useState({ name: "", password: "", confirm: "" });
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [pending, start] = useTransition();

  const fail = (message: string) => {
    setInfo(null);
    setError(message);
  };
  const sendCode = () =>
    start(async () => {
      const r = await signupCodeAction(email);
      if (!r.ok) return fail(r.error);
      setError(null);
      setInfo(`Te enviamos un código de 6 dígitos a ${email}. Vence en 10 minutos.`);
      setStep(2);
    });

  return (
    <main className="min-h-dvh bg-page">
      <div className={clsx("mx-auto grid max-w-6xl gap-10 px-6 py-10", step > 0 && "lg:grid-cols-[minmax(0,30rem)_minmax(0,1fr)]")}>
        <div className="flex flex-col gap-6">
          <div className="flex items-center justify-between">
            <span className="flex items-center gap-2.5">
              <span aria-hidden className="size-2.5 rounded-full bg-sol" />
              <span className="text-sm font-semibold tracking-tight text-fg-strong">Kuador Go</span>
            </span>
            <Link href="/login" className="k-link text-xs">
              Ya tengo cuenta
            </Link>
          </div>

          <div>
            <h1 className="text-xl font-semibold tracking-tight text-balance text-fg-strong">Crea el espacio de trabajo de tu empresa</h1>
            <p className="mt-1 text-[13px] text-fg-muted">Tu equipo y sus agentes de IA en un mismo tablero. Toma dos minutos.</p>
          </div>

          <ol aria-label="Pasos" className={clsx("grid gap-2", steps.length === 5 ? "grid-cols-5" : "grid-cols-4", step === 0 && "max-w-[30rem]")}>
            {steps.map((label, i) => (
              <li key={label} aria-current={i === shown ? "step" : undefined} className="flex flex-col gap-1.5">
                <span className={clsx("h-1 rounded-full", i < shown ? "bg-sol" : i === shown ? "bg-sol/60" : "bg-line-strong")} />
                <span className={clsx("flex items-center gap-1 text-[11px]", i === shown ? "font-medium text-fg-strong" : "text-fg-faint")}>
                  {i < shown && <Check className="size-3" aria-hidden />}
                  {label}
                </span>
              </li>
            ))}
          </ol>

          {step === 0 && (
            <div className="flex flex-col gap-5">
              <ul className={clsx("grid gap-4", plans.length >= 3 ? "lg:grid-cols-3" : "md:grid-cols-2")}>
                {plans.map((p) => (
                  <li key={p.code}>
                    <button
                      type="button"
                      aria-pressed={p.code === planCode}
                      onClick={() => setPlanCode(p.code)}
                      className="h-full w-full rounded-xl text-left outline-none focus-visible:ring-2 focus-visible:ring-sol"
                    >
                      <PlanCard plan={p} current={p.code === planCode} currentLabel="Elegido" />
                    </button>
                  </li>
                ))}
              </ul>
              <div className="flex flex-wrap items-center justify-between gap-3">
                <p className="text-[13px] text-fg-muted">
                  {plan && plan.priceMonthly > 0
                    ? plan.trialDays > 0
                      ? `Empiezas con ${plan.trialDays} días de prueba de ${plan.name}. Luego ${priceLabel(plan)} al mes; puedes cambiar de plan cuando quieras.`
                      : `${plan.name}: ${priceLabel(plan)} al mes. Mientras habilitamos el cobro en línea, el pago se activa a mano desde kuador.com.`
                    : "Puedes cambiar de plan cuando quieras desde Espacio de trabajo → Plan."}
                </p>
                <Button tone="primary" disabled={!plan} onClick={() => setStep(1)}>Continuar</Button>
              </div>
            </div>
          )}

          {step > 0 && (
          <div className="rounded-lg border border-line bg-surface p-6">
            {step === 1 && (
              <form className="flex flex-col gap-4" onSubmit={(e) => { e.preventDefault(); sendCode(); }}>
                <Field label="Correo de trabajo" hint="Será tu acceso. Te enviaremos un código para comprobar que es tuyo.">
                  {(id) => <TextInput id={id} type="email" required autoFocus autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="ana@clinicaandes.com" />}
                </Field>
                <Button type="submit" tone="primary" loading={pending} className="w-full">Enviar código</Button>
              </form>
            )}

            {step === 2 && (
              <form
                className="flex flex-col gap-4"
                onSubmit={(e) => {
                  e.preventDefault();
                  start(async () => {
                    const r = await signupVerifyAction(email, code);
                    if (!r.ok) return fail(r.error);
                    setToken(r.data.signupToken);
                    setError(null);
                    setInfo(null);
                    setStep(3);
                  });
                }}
              >
                <Field label="Código de verificación" hint={`Revisa ${email} (también la carpeta de spam).`}>
                  {(id) => (
                    <TextInput
                      id={id}
                      required
                      autoFocus
                      inputMode="numeric"
                      autoComplete="one-time-code"
                      pattern="\d{6}"
                      maxLength={6}
                      value={code}
                      onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))}
                      className="tabular text-center font-mono text-xl tracking-[0.5em]"
                    />
                  )}
                </Field>
                <Button type="submit" tone="primary" loading={pending} disabled={code.length !== 6} className="w-full">Verificar</Button>
                <div className="flex justify-between text-xs">
                  <button type="button" className="k-link" onClick={() => { setStep(1); setCode(""); setInfo(null); }}>Cambiar correo</button>
                  <button type="button" className="k-link" disabled={pending} onClick={sendCode}>Reenviar código</button>
                </div>
              </form>
            )}

            {step === 3 && (
              <form
                className="flex flex-col gap-5"
                onSubmit={(e) => {
                  e.preventDefault();
                  setError(null);
                  setStep(4);
                }}
              >
                <BrandFields
                  value={brand}
                  onChange={(next) => {
                    setBrand(next);
                    if (next.name !== brand.name && next.name.trim().length > 1) {
                      const name = next.name;
                      void signupSlugAction(name).then((r) => r.ok && setSlug(r.data.slug));
                    }
                  }}
                />
                <Field label="Identificador del espacio" hint="Minúsculas, números o guiones. Identifica a tu empresa en el tablero.">
                  {(id) => <TextInput id={id} required pattern="[a-z0-9][a-z0-9\-]{1,39}" maxLength={40} value={slug} onChange={(e) => setSlug(e.target.value.toLowerCase())} className="font-mono text-xs" />}
                </Field>
                <div className="flex justify-end">
                  <Button type="submit" tone="primary" disabled={!brand.name.trim() || !/^#[0-9a-f]{6}$/i.test(brand.brandColor)}>Continuar</Button>
                </div>
              </form>
            )}

            {step === 4 && (
              <form
                className="flex flex-col gap-4"
                onSubmit={(e) => {
                  e.preventDefault();
                  if (owner.password !== owner.confirm) return fail("Las contraseñas no coinciden.");
                  start(async () => {
                    const r = await signupCompleteAction({
                      signupToken: token,
                      email,
                      companyName: brand.name,
                      slug: slug || null,
                      description: brand.description.trim() || null,
                      brandColor: brand.brandColor.toLowerCase(),
                      brandOnColor: brand.brandOnColor,
                      logo: brand.logo,
                      ownerName: owner.name,
                      password: owner.password,
                      plan: planCode,
                      // Con un plan de pago, al terminar se va a pagarlo (o a seguir con la prueba).
                      next: plan && plan.priceMonthly > 0 ? "/workspace/plan?bienvenida=1" : undefined,
                    });
                    if (r && !r.ok) fail(r.error);
                  });
                }}
              >
                <p className="text-[13px] text-fg-muted">
                  Entrarás como <span className="font-medium text-fg-strong">{email}</span>, dueño o dueña de {brand.name}.
                </p>
                <Field label="Tu nombre">{(id) => <TextInput id={id} required autoFocus maxLength={120} autoComplete="name" value={owner.name} onChange={(e) => setOwner({ ...owner, name: e.target.value })} />}</Field>
                <Field label="Contraseña" hint="Mínimo 10 caracteres.">
                  {(id) => <PasswordInput id={id} required minLength={10} maxLength={128} autoComplete="new-password" value={owner.password} onChange={(e) => setOwner({ ...owner, password: e.target.value })} />}
                </Field>
                <Field label="Repite la contraseña">
                  {(id) => <PasswordInput id={id} required minLength={10} maxLength={128} autoComplete="new-password" value={owner.confirm} onChange={(e) => setOwner({ ...owner, confirm: e.target.value })} />}
                </Field>
                <div className="flex items-center justify-between">
                  <Button tone="ghost" icon={ArrowLeft} onClick={() => setStep(3)}>Empresa</Button>
                  <Button type="submit" tone="primary" loading={pending}>Crear espacio</Button>
                </div>
              </form>
            )}

            {(error || info) && (
              <p role={error ? "alert" : "status"} className={clsx("mt-4 rounded-md px-3 py-2 text-[13px]", error ? "bg-danger/10 text-danger" : "bg-info/10 text-fg")}>
                {error ?? info}
              </p>
            )}
          </div>
          )}
          {step > 0 && plan && (
            <p className="text-[13px] text-fg-muted">
              Plan <span className="font-medium text-fg-strong">{plan.name}</span>
              {plan.priceMonthly > 0 && ` · ${priceLabel(plan)} al mes`}{" "}
              {step < 4 && (
                <button type="button" className="k-link ml-1" onClick={() => setStep(0)}>
                  Cambiar
                </button>
              )}
            </p>
          )}
          <p className="text-xs text-fg-faint">Al crear el espacio se preparan el catálogo de fábrica (flujos, metodologías y roles) y un agente por cada rol.</p>
        </div>

        <aside aria-label="Vista previa" className={clsx("hidden flex-col gap-3 lg:pt-24", step > 0 && "lg:flex")}>
          <span className="text-xs font-medium text-fg-muted">Así se verá tu tablero</span>
          <BrandPreview value={brand} />
          {brand.description.trim() && <p className="max-w-md text-[13px] leading-relaxed text-fg-muted">{brand.description}</p>}
        </aside>
      </div>
    </main>
  );
}
