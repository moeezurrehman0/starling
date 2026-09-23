/* SPDX-License-Identifier: MIT */
import { SignedOut, Unavailable, viewerOrNull } from "@/app/shell";
import { Composer } from "@/components/Composer";
import { Nav } from "@/components/Nav";
import { Pager } from "@/components/Pager";
import { TweetCard } from "@/components/TweetCard";
import * as api from "@/lib/api";
import type { Page, Profile, TimelineTweet } from "@/lib/types";

/**
 * The home timeline.
 *
 * Rendered per request. Nothing about this page is cacheable: it is one specific user's fanned
 * -out timeline, keyed by a cookie, and a shared cache entry would be a cross-account leak.
 */
export const dynamic = "force-dynamic";

export default async function Home({
  searchParams,
}: {
  searchParams: Promise<{ cursor?: string }>;
}) {
  const { cursor } = await searchParams;
  const viewer = await viewerOrNull();

  let page: Page<TimelineTweet> | null = null;
  let authors = new Map<string, Profile>();
  if (viewer) {
    try {
      page = await api.homeTimeline(cursor);
      authors = await api.authorsOf(page.items);
    } catch {
      page = null;
    }
  }

  return (
    <>
      <Nav viewer={viewer} />
      <main className="mx-auto max-w-2xl">
        {!viewer ? (
          <SignedOut />
        ) : !page ? (
          <Unavailable what="Your timeline" />
        ) : (
          <>
            <Composer />
            {page.items.length === 0 ? (
              <p className="p-8 text-center text-sm text-slate-500" data-testid="empty-timeline">
                Nothing here yet. Follow someone, or post the first thing.
              </p>
            ) : (
              page.items.map((tweet) => (
                <TweetCard
                  key={tweet.id}
                  tweet={tweet}
                  author={authors.get(tweet.authorId)}
                  signedIn
                />
              ))
            )}
            <Pager basePath="/" nextCursor={page.nextCursor} />
          </>
        )}
      </main>
    </>
  );
}
