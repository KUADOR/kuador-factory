import type { Metadata } from "next";

import { PageHeader } from "@/components/shell/PageHeader";
import { Avatar } from "@/components/ui/Avatar";
import { Section } from "@/components/ui/Section";
import { boardApi } from "@/lib/api";
import { roleLabel } from "@/lib/labels";
import { failPage } from "@/lib/page-errors";
import type { Me } from "@/lib/types";

import { ChangePasswordForm } from "./ChangePasswordForm";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Mi cuenta" };

export default async function AccountPage() {
  let me: Me;
  try {
    me = await boardApi.me();
  } catch (e) {
    return failPage(e);
  }
  return (
    <>
      <PageHeader
        width="narrow"
        title={
          <span className="flex items-center gap-3">
            <Avatar member={me} size="lg" /> {me.name}
          </span>
        }
        description={roleLabel(me.role)}
      />
      <div className="max-w-xl px-6 py-6">
        <Section title="Cambiar contraseña" description="Al cambiarla se cierran tus otras sesiones abiertas.">
          <ChangePasswordForm />
        </Section>
      </div>
    </>
  );
}
