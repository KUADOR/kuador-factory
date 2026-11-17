import { SettingsArea } from "@/components/shell/SettingsArea";

export default function Layout({ children }: { children: React.ReactNode }) {
  return <SettingsArea>{children}</SettingsArea>;
}
