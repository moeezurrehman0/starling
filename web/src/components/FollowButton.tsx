/* SPDX-License-Identifier: MIT */
"use client";

import { useTransition } from "react";

import { followAction } from "@/app/actions";

/** Follow / unfollow, with the pending state visible because fan-out makes the write slow. */
export function FollowButton({
  userId,
  handle,
  following,
}: {
  userId: string;
  handle: string;
  following: boolean;
}) {
  const [pending, startTransition] = useTransition();

  return (
    <button
      type="button"
      disabled={pending}
      data-testid="follow-button"
      onClick={() => startTransition(() => void followAction(userId, handle, following))}
      className={`rounded-full px-4 py-1.5 text-sm font-medium disabled:opacity-50 ${
        following
          ? "border border-slate-300 text-slate-700 hover:border-red-400 hover:text-red-600 dark:border-slate-700 dark:text-slate-200"
          : "bg-slate-900 text-white hover:bg-slate-700 dark:bg-white dark:text-slate-900"
      }`}
    >
      {following ? "Following" : "Follow"}
    </button>
  );
}
