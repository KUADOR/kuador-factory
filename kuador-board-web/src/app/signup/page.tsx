import type { Metadata } from "next";

import { SignupFlow } from "./SignupFlow";

export const metadata: Metadata = { title: "Crear espacio de trabajo" };

export default function SignupPage() {
  return <SignupFlow />;
}
