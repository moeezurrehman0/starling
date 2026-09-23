/* SPDX-License-Identifier: MIT */
import { notFound } from "next/navigation";

import { Unavailable, viewerOrNull } from "@/app/shell";
import { FollowButton } from "@/components/FollowButton";
import { Nav } from "@/components/Nav";
import { Pager } from "@/components/Pager";
import { TweetCard } from "@/components/TweetCard";
import * as api from "@/lib/api";
import { ApiError } from "@/lib/api";
import type { Page, Tweet } from "@/lib/types";

export const dynamic = "force-dynamic";

/**
 * A public profile and its tweets.
 *
 * The handle is the URL key rather than the id, because that is what a person types and links
 * to. Everything downstream uses the id the lookup returns — handles are mutable in principle
 * and ids are not.
 */
export default async function ProfilePage({
  params,
  searchParams,
}: {
  params: Promise<{ handle: string }>;
  searchParams: Promise<{ cursor?: string }>;
}) {
  const { handle } = await params;
  const { cursor } = await searchParams;
  const viewer = await viewerOrNull();

  let profile;
  try {
    profile = await api.profileByHandle(handle);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      notFound();
    }
    return (
      <>
        <Nav viewer={viewer} />
        <main className="mx-auto max-w-2xl">
          <Unavailable what="This profile" />
        </main>
      </>
    );
  }

  // Both are independent of each other, so they overlap rather than queue. Neither is allowed
  // to take the page down: a profile that renders without its tweets is still useful.
  const [page, followState] = await Promise.all([
    api.tweetsByAuthor(profile.id, cursor).catch(() => null as Page<Tweet> | null),
    viewer && viewer.id !== profile.id
      ? api.isFollowing(profile.id).catch(() => null)
      : Promise.resolve(null),
  ]);

  return (
    <>
      <Nav viewer={viewer} />
      <main className="mx-auto max-w-2xl">
        <section className="border-b border-slate-200 p-4 dark:border-slate-800">
          <div className="flex items-start justify-between gap-4">
            <div>
              <h1 className="text-xl font-bold" data-testid="profile-name">
                {profile.displayName}
              </h1>
              <p className="text-sm text-slate-500">@{profile.handle}</p>
              {profile.bio ? <p className="mt-2 text-sm">{profile.bio}</p> : null}
              <p className="mt-2 text-sm text-slate-500">
                <span data-testid="follower-count">{profile.followerCount}</span> followers
              </p>
              {profile.celebrity ? (
                <p className="mt-2 text-xs text-amber-700 dark:text-amber-300">
                  Celebrity account — their tweets are merged into a follower&apos;s timeline
                  when it is read, not written into it, so they can appear a moment late.
                </p>
              ) : null}
            </div>
            {followState ? (
              <FollowButton
                userId={profile.id}
                handle={profile.handle}
                following={followState.following}
              />
            ) : null}
          </div>
        </section>

        {!page ? (
          <Unavailable what="These tweets" />
        ) : page.items.length === 0 ? (
          <p className="p-8 text-center text-sm text-slate-500">No tweets yet.</p>
        ) : (
          page.items.map((tweet) => (
            <TweetCard
              key={tweet.id}
              tweet={tweet}
              author={profile}
              signedIn={viewer !== null}
            />
          ))
        )}
        <Pager basePath={`/u/${profile.handle}`} nextCursor={page?.nextCursor ?? null} />
      </main>
    </>
  );
}
