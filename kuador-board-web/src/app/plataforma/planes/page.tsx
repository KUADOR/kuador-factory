import type { Metadata } from "next";

import { PlansEditor } from "@/components/billing/PlansEditor";
import { ReminderSettings } from "@/components/billing/ReminderSettings";
import { PageHeader } from "@/components/shell/PageHeader";
import { boardApi } from "@/lib/api";
import { failPage } from "@/lib/page-errors";

export const dynamic = "force-dynamic";
export const metadata: Metadata = { title: "Planes y avisos" };

export default async function PlatformPlansPage() {
  let data;
  try {
    const [plans, settings] = await Promise.all([boardApi.platformPlans(), boardApi.billingSettings()]);
    data = { plans, settings };
  } catch (e) {
    return failPage(e);
  }
  return (
    <>
      <PageHeader title="Planes y avisos" description="Lo que se vende y cuándo se avisa a cada empresa que su plan está por vencer." />
      <div className="flex flex-col gap-6 px-6 py-6">
        <PlansEditor plans={data.plans} />
        <ReminderSettings days={data.settings.reminderDays} />
      </div>
    </>
  );
}
