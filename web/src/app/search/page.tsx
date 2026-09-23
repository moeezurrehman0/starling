/* SPDX-License-Identifier: MIT */
import { Nav } from "@/components/Nav";
import { Pager } from "@/components/Pager";
import { TweetCard } from "@/components/TweetCard";
import { viewerOrNull, Unavailable } from "@/app/shell";
import * as api from "@/lib/api";
import type { Page, Profile, Tweet } from "@/lib/types";

export const dynamic = "force-dynamic";

/**
 * Full-text search.
 *
 * Open to signed-out visitors, matching the service: searching is a read of public tweets.
 * Results come from the Postgres index but are hydrated from DynamoDB by tweet-service, so a
 * tweet deleted since it was indexed simply does not appear.
 */
export default async function SearchPage({
  searchParams,
}: {
  searchParams: Promise<{ q?: string; cursor?: string }>;
}) {
  const { q = "", cursor } = await searchParams;
  const query = q.trim();
  const viewer = await viewerOrNull();

  let page: Page<Tweet> | null = null;
  let authors = new Map<string, Profile>();
  let failed = false;
  if (query) {
    try {
      page = await api.searchTweets(query, cursor);
      authors = await api.authorsOf(page.items);
    } catch {
      failed = true;
    }
  }

  return (
    <>
      <Nav viewer={viewer} />
      <main className="mx-auto max-w-2xl">
        {!query ? (
          <p className="p-8 text-center text-sm text-slate-500">Type something to search.</p>
        ) : failed ? (
          <Unavailable what="Search" />
        ) : page && page.items.length === 0 ? (
          <p className="p-8 text-center text-sm text-slate-500" data-testid="no-results">
            No tweets match “{query}”.
          </p>
        ) : (
          <>
            <p className="border-b border-slate-200 p-4 text-sm text-slate-500 dark:border-slate-800">
              Results for “{query}”
            </p>
            {page?.items.map((tweet) => (
              <TweetCard
                key={tweet.id}
                tweet={tweet}
                author={authors.get(tweet.authorId)}
                signedIn={viewer !== null}
              />
            ))}
            <Pager basePath="/search" params={{ q: query }} nextCursor={page?.nextCursor ?? null} />
          </>
        )}
      </main>
    </>
  );
}
