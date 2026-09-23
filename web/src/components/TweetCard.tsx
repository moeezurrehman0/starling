/* SPDX-License-Identifier: MIT */
import Link from "next/link";

import { LikeButton } from "@/components/LikeButton";
import type { Profile, Tweet } from "@/lib/types";

/** Accepts both a tweet-service `Tweet` and a timeline-service `TweetView`. */
export type AnyTweet = Omit<Tweet, "likedByMe"> & { likedByMe?: boolean | null };

/**
 * Relative time, rendered on the server.
 *
 * Deliberately coarse. A precise "3 seconds ago" computed during server rendering is wrong the
 * moment it is cached, and correcting it would mean shipping a ticking client component onto
 * every row. The absolute timestamp is in the `title`, which is what anyone checking actually
 * wants.
 */
function ago(iso: string): string {
  const seconds = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
  if (seconds < 60) return "now";
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h`;
  if (seconds < 604800) return `${Math.floor(seconds / 86400)}d`;
  return new Date(iso).toLocaleDateString();
}

export function TweetCard({
  tweet,
  author,
  signedIn,
}: {
  tweet: AnyTweet;
  author: Profile | undefined;
  signedIn: boolean;
}) {
  return (
    <article
      data-testid="tweet"
      data-tweet-id={tweet.id}
      className="border-b border-slate-200 p-4 dark:border-slate-800"
    >
      <div className="flex items-baseline gap-2 text-sm">
        {author ? (
          <>
            <Link href={`/u/${author.handle}`} className="font-semibold hover:underline">
              {author.displayName}
            </Link>
            <span className="text-slate-500">@{author.handle}</span>
            {author.celebrity ? (
              <span
                title="Celebrity account — their tweets are merged into your timeline at read time"
                className="rounded bg-amber-100 px-1.5 py-0.5 text-xs font-medium text-amber-800 dark:bg-amber-900/40 dark:text-amber-200"
              >
                celebrity
              </span>
            ) : null}
          </>
        ) : (
          // An author that failed to resolve is shown as its id rather than collapsing the row.
          <span className="font-mono text-xs text-slate-500">{tweet.authorId}</span>
        )}
        <span className="text-slate-400" title={tweet.createdAt}>
          · {ago(tweet.createdAt)}
        </span>
      </div>

      <p className="mt-1 whitespace-pre-wrap break-words">{tweet.text}</p>

      {tweet.mediaKeys.length > 0 ? (
        <p className="mt-2 text-xs text-slate-500">
          {tweet.mediaKeys.length} attachment{tweet.mediaKeys.length > 1 ? "s" : ""}
        </p>
      ) : null}

      <div className="mt-2">
        <LikeButton
          tweetId={tweet.id}
          likeCount={tweet.likeCount}
          likedByMe={tweet.likedByMe ?? null}
          signedIn={signedIn}
        />
      </div>
    </article>
  );
}
