/* SPDX-License-Identifier: MIT */
import Link from "next/link";

import * as api from "@/lib/api";
import { ApiError } from "@/lib/api";
import { sessionToken } from "@/lib/session";
import type { Profile } from "@/lib/types";

/**
 * The viewer, or null when signed out.
 *
 * A token that the user-service rejects — expired, or signed by a key that rotated while the
 * cookie sat in a browser — is treated as signed out rather than as an error. The alternative
 * is an error page that the user cannot escape without clearing cookies by hand.
 */
export async function viewerOrNull(): Promise<Profile | null> {
  if (!(await sessionToken())) {
    return null;
  }
  try {
    return await api.me();
  } catch (error) {
    if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
      return null;
    }
    throw error;
  }
}

/** Shown in place of a feed when the API is unreachable, instead of an error page. */
export function Unavailable({ what }: { what: string }) {
  return (
    <div className="p-8 text-center text-sm text-slate-500" data-testid="unavailable">
      <p className="font-medium">{what} is unavailable right now.</p>
      <p className="mt-1">The API did not respond. Try again in a moment.</p>
    </div>
  );
}

/** Signed-out home. */
export function SignedOut() {
  return (
    <div className="p-8 text-center" data-testid="signed-out">
      <h1 className="text-2xl font-bold">See what&apos;s happening</h1>
      <p className="mt-2 text-sm text-slate-500">
        Sign in to read your timeline, or search without an account.
      </p>
      <div className="mt-6 flex justify-center gap-3">
        <Link
          href="/login"
          className="rounded-full bg-sky-600 px-5 py-2 text-sm font-medium text-white hover:bg-sky-700"
        >
          Sign in
        </Link>
        <Link
          href="/signup"
          className="rounded-full border border-slate-300 px-5 py-2 text-sm font-medium hover:bg-slate-50 dark:border-slate-700 dark:hover:bg-slate-900"
        >
          Create account
        </Link>
      </div>
    </div>
  );
}
