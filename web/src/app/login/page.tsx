/* SPDX-License-Identifier: MIT */
import { redirect } from "next/navigation";

import { viewerOrNull } from "@/app/shell";
import { CredentialsForm } from "@/components/CredentialsForm";

export const dynamic = "force-dynamic";

export default async function LoginPage() {
  // A signed-in user landing on /login is a stale bookmark, not a request to sign in twice.
  if (await viewerOrNull()) {
    redirect("/");
  }
  return (
    <main className="mx-auto max-w-sm px-4 py-16">
      <h1 className="mb-6 text-center text-2xl font-bold">Sign in to chirp</h1>
      <CredentialsForm mode="login" />
    </main>
  );
}
