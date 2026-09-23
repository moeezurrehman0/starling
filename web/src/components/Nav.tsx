/* SPDX-License-Identifier: MIT */
import Link from "next/link";

import { logoutAction } from "@/app/actions";
import type { Profile } from "@/lib/types";

/** The single persistent chrome: brand, search, and either the account or a sign-in link. */
export function Nav({ viewer }: { viewer: Profile | null }) {
  return (
    <header className="sticky top-0 z-10 border-b border-slate-200 bg-white/80 backdrop-blur dark:border-slate-800 dark:bg-slate-950/80">
      <nav className="mx-auto flex max-w-2xl items-center gap-4 px-4 py-3">
        <Link href="/" className="text-lg font-bold tracking-tight">
          chirp
        </Link>

        <form action="/search" className="flex-1">
          <input
            type="search"
            name="q"
            placeholder="Search tweets"
            aria-label="Search tweets"
            className="w-full rounded-full border border-slate-300 bg-slate-50 px-4 py-1.5 text-sm outline-none focus:border-sky-500 dark:border-slate-700 dark:bg-slate-900"
          />
        </form>

        {viewer ? (
          <div className="flex items-center gap-3 text-sm">
            <Link href={`/u/${viewer.handle}`} className="font-medium hover:underline">
              @{viewer.handle}
            </Link>
            <form action={logoutAction}>
              <button type="submit" className="text-slate-500 hover:underline">
                Sign out
              </button>
            </form>
          </div>
        ) : (
          <Link
            href="/login"
            className="rounded-full bg-sky-600 px-4 py-1.5 text-sm font-medium text-white hover:bg-sky-700"
          >
            Sign in
          </Link>
        )}
      </nav>
    </header>
  );
}
