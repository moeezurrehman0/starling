/* SPDX-License-Identifier: MIT */
"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";

import * as api from "@/lib/api";
import { ApiError } from "@/lib/api";
import type { FormState } from "@/lib/forms";
import { endSession, startSession } from "@/lib/session";

function messageFor(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    if (error.status === 401) {
      return "Wrong handle or password.";
    }
    if (error.status === 409) {
      return "That handle is already taken.";
    }
    if (error.status === 429) {
      return "Too many attempts. Wait a moment and try again.";
    }
    if (error.status === 503) {
      return "The service is unreachable right now.";
    }
    return error.detail ?? fallback;
  }
  return fallback;
}

export async function loginAction(_previous: FormState, form: FormData): Promise<FormState> {
  const handle = String(form.get("handle") ?? "").trim();
  const password = String(form.get("password") ?? "");
  if (!handle || !password) {
    return { error: "Handle and password are both required." };
  }

  try {
    const token = await api.login(handle, password);
    await startSession(token.accessToken, token.expiresIn);
  } catch (error) {
    return { error: messageFor(error, "Could not sign in.") };
  }
  // Outside the try: redirect() signals by throwing, and catching it here would turn a
  // successful sign-in into an error message.
  redirect("/");
}

export async function signupAction(_previous: FormState, form: FormData): Promise<FormState> {
  const handle = String(form.get("handle") ?? "").trim();
  const displayName = String(form.get("displayName") ?? "").trim();
  const password = String(form.get("password") ?? "");

  if (!/^[A-Za-z0-9_]{3,15}$/.test(handle)) {
    return { error: "Handle must be 3-15 letters, digits or underscores." };
  }
  if (!displayName) {
    return { error: "A display name is required." };
  }
  // Mirrors user-service's @Size(min = 12). Checked here too so the user is told before a
  // round-trip, not instead of one — the service remains the authority.
  if (password.length < 12) {
    return { error: "Password must be at least 12 characters." };
  }

  try {
    await api.register(handle, displayName, password);
    const token = await api.login(handle, password);
    await startSession(token.accessToken, token.expiresIn);
  } catch (error) {
    return { error: messageFor(error, "Could not create the account.") };
  }
  redirect("/");
}

export async function logoutAction(): Promise<void> {
  await endSession();
  redirect("/login");
}

export async function postTweetAction(_previous: FormState, form: FormData): Promise<FormState> {
  const text = String(form.get("text") ?? "").trim();
  if (!text) {
    return { error: "Say something first." };
  }
  // Code points, not UTF-16 units, matching TweetItem.MAX_TEXT_LENGTH. An emoji is one
  // character here and one character there.
  if ([...text].length > 280) {
    return { error: "That is longer than 280 characters." };
  }

  try {
    await api.postTweet(text);
  } catch (error) {
    return { error: messageFor(error, "Could not post that.") };
  }
  // The new tweet reaches the author's own home timeline through fan-out, which is
  // asynchronous — revalidating both paths is what makes it appear on the next render
  // rather than on a manual refresh.
  revalidatePath("/");
  return { error: null, succeededAt: Date.now() };
}

export async function likeAction(tweetId: string, liked: boolean): Promise<void> {
  try {
    if (liked) {
      await api.unlike(tweetId);
    } else {
      await api.like(tweetId);
    }
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      redirect("/login");
    }
    throw error;
  }
  revalidatePath("/");
  // Also every profile page and the search results. A like can be clicked from any of the
  // three, and revalidating only "/" meant the button stayed on "Like" until the user
  // navigated away and back -- the write had happened, so a second click then sent an unlike.
  // The bracketed form invalidates the dynamic route rather than one rendered instance, which
  // is what is wanted here: the action does not know, and should not need to know, whose
  // profile the click came from.
  revalidatePath("/u/[handle]", "page");
  revalidatePath("/search");
}

export async function followAction(
  userId: string,
  handle: string,
  following: boolean,
): Promise<void> {
  try {
    if (following) {
      await api.unfollow(userId);
    } else {
      await api.follow(userId);
    }
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      redirect("/login");
    }
    throw error;
  }
  revalidatePath(`/u/${handle}`);
  revalidatePath("/");
}

export async function deleteTweetAction(tweetId: string): Promise<void> {
  await api.deleteTweet(tweetId);
  revalidatePath("/");
}
