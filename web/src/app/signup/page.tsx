/* SPDX-License-Identifier: MIT */
import { redirect } from "next/navigation";

import { viewerOrNull } from "@/app/shell";
import { CredentialsForm } from "@/components/CredentialsForm";

export const dynamic = "force-dynamic";

export default async function SignupPage() {
  if (await viewerOrNull()) {
    redirect("/");
  }
  return (
    <main className="mx-auto max-w-sm px-4 py-16">
      <h1 className="mb-6 text-center text-2xl font-bold">Create your account</h1>
      <CredentialsForm mode="signup" />
    </main>
  );
}
