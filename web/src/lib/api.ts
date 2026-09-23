/* SPDX-License-Identifier: MIT */
import "server-only";

import { sessionToken } from "@/lib/session";
import type {
  FollowState,
  LikeState,
  Page,
  Profile,
  TimelineTweet,
  TokenResponse,
  Tweet,
} from "@/lib/types";

/**
 * Every call goes to the gateway, never to a service directly.
 *
 * The frontend therefore knows one address instead of four, and inherits the gateway's rate
 * limiting, request-id generation and token validation for free. In Compose this is the
 * gateway container; in Kubernetes it is the gateway Service.
 */
const API_BASE = process.env.API_BASE ?? "http://localhost:8080";

/**
 * Upstream calls are bounded.
 *
 * Without this a hung gateway would hold a server-rendering request open until the platform's
 * own timeout, turning one slow dependency into an exhausted request pool.
 */
const TIMEOUT_MS = Number(process.env.API_TIMEOUT_MS ?? 5000);

/** A non-2xx response from the API, carrying enough to render a useful message. */
export class ApiError extends Error {
  readonly status: number;
  readonly detail: string | null;

  constructor(status: number, detail: string | null) {
    super(`API returned ${status}`);
    this.name = "ApiError";
    this.status = status;
    this.detail = detail;
  }
}

interface RequestOptions {
  method?: string;
  body?: unknown;
  /** Attach the caller's token. Reads of public data deliberately do not. */
  authenticated?: boolean;
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = "GET", body, authenticated = false } = options;

  const headers: Record<string, string> = { accept: "application/json" };
  if (body !== undefined) {
    headers["content-type"] = "application/json";
  }
  if (authenticated) {
    const token = await sessionToken();
    if (!token) {
      throw new ApiError(401, "not signed in");
    }
    headers.authorization = `Bearer ${token}`;
  }

  let response: Response;
  try {
    response = await fetch(`${API_BASE}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(TIMEOUT_MS),
      // Personalised, authenticated and cursor-paged. Caching any of it would serve one
      // user's timeline to another.
      cache: "no-store",
    });
  } catch (cause) {
    // A refused connection, a DNS failure and a timeout are all "the API is unreachable" to
    // the page rendering above; 503 is the honest status for that.
    throw new ApiError(503, cause instanceof Error ? cause.message : "upstream unreachable");
  }

  if (!response.ok) {
    throw new ApiError(response.status, await detailOf(response));
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

/** Best-effort extraction of a problem-detail message; never throws. */
async function detailOf(response: Response): Promise<string | null> {
  try {
    const text = await response.text();
    if (!text) {
      return null;
    }
    const parsed = JSON.parse(text) as { detail?: string; message?: string };
    return parsed.detail ?? parsed.message ?? text.slice(0, 200);
  } catch {
    return null;
  }
}

function query(params: Record<string, string | number | undefined | null>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== "") {
      search.set(key, String(value));
    }
  }
  const rendered = search.toString();
  return rendered ? `?${rendered}` : "";
}

/* ---------------------------------------------------------------- accounts */

export function register(
  handle: string,
  displayName: string,
  password: string,
): Promise<Profile> {
  return request<Profile>("/v1/users", {
    method: "POST",
    body: { handle, displayName, password },
  });
}

export function login(handle: string, password: string): Promise<TokenResponse> {
  return request<TokenResponse>("/v1/sessions", {
    method: "POST",
    body: { handle, password },
  });
}

export function me(): Promise<Profile> {
  return request<Profile>("/v1/users/me", { authenticated: true });
}

export function profileByHandle(handle: string): Promise<Profile> {
  return request<Profile>(`/v1/users/by-handle/${encodeURIComponent(handle)}`);
}

export function profileById(userId: string): Promise<Profile> {
  return request<Profile>(`/v1/users/${encodeURIComponent(userId)}`);
}

export function follow(userId: string): Promise<FollowState> {
  return request<FollowState>(`/v1/users/${encodeURIComponent(userId)}/followers`, {
    method: "POST",
    authenticated: true,
  });
}

export function unfollow(userId: string): Promise<FollowState> {
  return request<FollowState>(`/v1/users/${encodeURIComponent(userId)}/followers`, {
    method: "DELETE",
    authenticated: true,
  });
}

export function isFollowing(userId: string): Promise<FollowState> {
  return request<FollowState>(`/v1/users/${encodeURIComponent(userId)}/followers/me`, {
    authenticated: true,
  });
}

/* ------------------------------------------------------------------ tweets */

export function postTweet(text: string, replyTo?: string, retweetOf?: string): Promise<Tweet> {
  return request<Tweet>("/v1/tweets", {
    method: "POST",
    body: { text, mediaKeys: [], replyTo: replyTo ?? null, retweetOf: retweetOf ?? null },
    authenticated: true,
  });
}

export function deleteTweet(id: string): Promise<void> {
  return request<void>(`/v1/tweets/${encodeURIComponent(id)}`, {
    method: "DELETE",
    authenticated: true,
  });
}

export function tweetsByAuthor(
  authorId: string,
  cursor?: string,
  limit = 20,
): Promise<Page<Tweet>> {
  return request<Page<Tweet>>(
    `/v1/tweets/by-author/${encodeURIComponent(authorId)}${query({ cursor, limit })}`,
  );
}

export function like(id: string): Promise<LikeState> {
  return request<LikeState>(`/v1/tweets/${encodeURIComponent(id)}/likes`, {
    method: "POST",
    authenticated: true,
  });
}

export function unlike(id: string): Promise<LikeState> {
  return request<LikeState>(`/v1/tweets/${encodeURIComponent(id)}/likes`, {
    method: "DELETE",
    authenticated: true,
  });
}

/* --------------------------------------------------------- timeline, search */

export function homeTimeline(cursor?: string, limit = 20): Promise<Page<TimelineTweet>> {
  return request<Page<TimelineTweet>>(`/v1/timelines/home${query({ cursor, limit })}`, {
    authenticated: true,
  });
}

export function searchTweets(q: string, cursor?: string, limit = 20): Promise<Page<Tweet>> {
  return request<Page<Tweet>>(`/v1/search/tweets${query({ q, cursor, limit })}`);
}

/* ------------------------------------------------------------- author names */

/**
 * Resolves author ids to profiles for a rendered page.
 *
 * Tweets carry an `authorId` and nothing else — the services deliberately do not denormalise a
 * display name onto every tweet, because a rename would then have to rewrite history. That
 * pushes the join here. Ids are deduplicated first, so a page of twenty tweets from three
 * authors costs three calls rather than twenty, and a failure to resolve one author degrades
 * to showing the raw id instead of failing the page.
 */
export async function authorsOf(
  tweets: ReadonlyArray<{ authorId: string }>,
): Promise<Map<string, Profile>> {
  const ids = [...new Set(tweets.map((tweet) => tweet.authorId))];
  const resolved = await Promise.all(
    ids.map(async (id) => {
      try {
        return await profileById(id);
      } catch {
        return null;
      }
    }),
  );
  const byId = new Map<string, Profile>();
  for (const profile of resolved) {
    if (profile) {
      byId.set(profile.id, profile);
    }
  }
  return byId;
}
