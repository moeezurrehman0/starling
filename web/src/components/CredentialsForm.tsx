/* SPDX-License-Identifier: MIT */
"use client";

import Link from "next/link";
import { useActionState } from "react";
import { useFormStatus } from "react-dom";

import { loginAction, signupAction } from "@/app/actions";
import { NO_ERROR, type FormState } from "@/lib/forms";

function Submit({ label }: { label: string }) {
  const { pending } = useFormStatus();
  return (
    <button
      type="submit"
      disabled={pending}
      className="w-full rounded-full bg-sky-600 px-5 py-2 text-sm font-medium text-white hover:bg-sky-700 disabled:opacity-50"
    >
      {pending ? "Working…" : label}
    </button>
  );
}

const FIELD =
  "w-full rounded-lg border border-slate-300 bg-transparent px-3 py-2 text-sm outline-none focus:border-sky-500 dark:border-slate-700";

/** Sign-in and sign-up share every line except three fields, so they share a component. */
export function CredentialsForm({ mode }: { mode: "login" | "signup" }) {
  const action = mode === "login" ? loginAction : signupAction;
  const [state, formAction] = useActionState<FormState, FormData>(action, NO_ERROR);

  return (
    <form action={formAction} className="space-y-3" data-testid={`${mode}-form`}>
      <input
        name="handle"
        autoComplete="username"
        placeholder="handle"
        aria-label="Handle"
        className={FIELD}
      />
      {mode === "signup" ? (
        <input
          name="displayName"
          autoComplete="name"
          placeholder="display name"
          aria-label="Display name"
          className={FIELD}
        />
      ) : null}
      <input
        name="password"
        type="password"
        autoComplete={mode === "login" ? "current-password" : "new-password"}
        placeholder="password"
        aria-label="Password"
        className={FIELD}
      />

      {state.error ? (
        <p role="alert" data-testid="form-error" className="text-sm text-red-600">
          {state.error}
        </p>
      ) : null}

      <Submit label={mode === "login" ? "Sign in" : "Create account"} />

      <p className="pt-2 text-center text-sm text-slate-500">
        {mode === "login" ? (
          <>
            No account?{" "}
            <Link href="/signup" className="text-sky-600 hover:underline">
              Create one
            </Link>
          </>
        ) : (
          <>
            Already have one?{" "}
            <Link href="/login" className="text-sky-600 hover:underline">
              Sign in
            </Link>
          </>
        )}
      </p>
    </form>
  );
}
