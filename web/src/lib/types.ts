/* SPDX-License-Identifier: MIT */

/**
 * Wire types, hand-mirrored from the services' `Api` records.
 *
 * Deliberately not generated. There is no OpenAPI document in this repo yet, and a generator
 * would have to run against a live service, which would make `npm run build` depend on the
 * backend being up. These are small enough to keep honest by hand; the e2e suite is what
 * actually catches drift.
 */

/** A public profile — user-service `Api.Profile`. */
export interface Profile {
  id: string;
  handle: string;
  displayName: string;
  bio: string | null;
  avatarUrl: string | null;
  followerCount: number;
  /**
   * Celebrity accounts are read-fanned rather than write-fanned, so their tweets can lag a
   * moment behind in a follower's timeline. The UI says so rather than looking broken.
   */
  celebrity: boolean;
  createdAt: string;
}

/** A tweet — tweet-service `Api.Tweet`. `likedByMe` is absent for anonymous reads. */
export interface Tweet {
  id: string;
  authorId: string;
  text: string;
  mediaKeys: string[];
  replyTo: string | null;
  retweetOf: string | null;
  createdAt: string;
  likeCount: number;
  likedByMe: boolean | null;
}

/**
 * A timeline entry — timeline-service `TweetView`.
 *
 * Structurally a `Tweet` minus `likedByMe`: timeline-service reads tweets through a
 * service-to-service call that carries no end-user principal, so it cannot know the
 * viewer's like state.
 */
export type TimelineTweet = Omit<Tweet, "likedByMe">;

/** One page of anything, with an opaque continuation cursor. */
export interface Page<T> {
  items: T[];
  nextCursor: string | null;
}

/** user-service `Api.FollowState`. */
export interface FollowState {
  following: boolean;
}

/** tweet-service `Api.LikeState`. */
export interface LikeState {
  likeCount: number;
  likedByMe: boolean;
}

/** user-service `Api.TokenResponse`. */
export interface TokenResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
}
