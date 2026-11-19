import type { Metadata } from "next";

import { PageHeader } from "@/components/shell/PageHeader";
import { Avatar } from "@/components/ui/Avatar";
import { Section } from "@/components/ui/Section";
import { boardApi } from "@/lib/api";
import { roleLabel } from "@/lib/labels";
import { failPage } from "@/lib/page-errors";
import type { Me, NotificationView } from "@/lib/types";

import { ChangePasswordForm } from "./ChangePasswordForm";
import { NotificationSettingsForm } from "./NotificationSettingsForm";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Mi cuenta" };

export default async function AccountPage() {
  let me: Me, notifications: NotificationView | null;
  try {
    me = await boardApi.me();
    notifications = me.kind === "HUMAN" ? await boardApi.notifications() : null;
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
      <div className="flex max-w-xl flex-col gap-5 px-4 py-5 sm:px-6 sm:py-6">
        {notifications && (
          <Section title="Notificaciones">
            <NotificationSettingsForm initial={notifications} />
          </Section>
        )}
        <Section title="Cambiar contraseña" description="Al cambiarla se cierran tus otras sesiones abiertas.">
          <ChangePasswordForm />
        </Section>
      </div>
    </>
  );
}
