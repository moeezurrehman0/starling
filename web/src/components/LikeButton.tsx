/* SPDX-License-Identifier: MIT */
"use client";

import { useTransition } from "react";

import { likeAction } from "@/app/actions";

/**
 * Like toggle.
 *
 * `likedByMe` is null on anonymous and timeline reads — timeline-service fetches tweets
 * without an end-user principal and so cannot know. The button renders the count either way
 * and only shows filled state when the answer is actually known.
 */
export function LikeButton({
  tweetId,
  likeCount,
  likedByMe,
  signedIn,
}: {
  tweetId: string;
  likeCount: number;
  likedByMe: boolean | null;
  signedIn: boolean;
}) {
  const [pending, startTransition] = useTransition();
  const liked = likedByMe === true;

  return (
    <button
      type="button"
      disabled={pending || !signedIn}
      aria-pressed={liked}
      aria-label={liked ? "Unlike" : "Like"}
      data-testid={`like-${tweetId}`}
      onClick={() => startTransition(() => void likeAction(tweetId, liked))}
      className={`flex items-center gap-1.5 text-sm transition-colors disabled:opacity-50 ${
        liked ? "text-pink-600" : "text-slate-500 hover:text-pink-600"
      }`}
    >
      <span aria-hidden>{liked ? "♥" : "♡"}</span>
      <span data-testid={`like-count-${tweetId}`}>{likeCount}</span>
    </button>
  );
}
