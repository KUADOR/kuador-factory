import { OrganizationArea } from "@/components/shell/OrganizationArea";
import { requireAdmin } from "@/lib/require-editor";

export default async function Layout({ children }: { children: React.ReactNode }) {
  await requireAdmin();
  return <OrganizationArea>{children}</OrganizationArea>;
}
