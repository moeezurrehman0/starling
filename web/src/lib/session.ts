/* SPDX-License-Identifier: MIT */
import "server-only";

import { cookies } from "next/headers";

/**
 * The session cookie.
 *
 * The access token lives in an `httpOnly` cookie and is attached to upstream calls on the
 * server. It is never serialised into a client component's props and never reaches browser
 * JavaScript, so an XSS bug in this app cannot read it. The cost is that every authenticated
 * call is a server round-trip — acceptable for a server-rendered timeline, and the reason the
 * whole UI is built from server components and server actions rather than a client-side SPA.
 */
const COOKIE = "tc_session";

/**
 * Persists a freshly issued token.
 *
 * `maxAge` mirrors the token's own lifetime, so the cookie and the credential expire together.
 * A cookie outliving its token produces the worst failure mode there is: a UI that believes it
 * is logged in and 401s on every action.
 */
export async function startSession(
  accessToken: string,
  expiresInSeconds: number,
): Promise<void> {
  const jar = await cookies();
  jar.set(COOKIE, accessToken, {
    httpOnly: true,
    sameSite: "lax",
    // Off in local http development, on everywhere a TLS-terminating ingress exists.
    secure: process.env.NODE_ENV === "production",
    path: "/",
    maxAge: expiresInSeconds,
  });
}

/** Drops the session cookie. */
export async function endSession(): Promise<void> {
  const jar = await cookies();
  jar.delete(COOKIE);
}

/** The caller's access token, or null when logged out. */
export async function sessionToken(): Promise<string | null> {
  const jar = await cookies();
  return jar.get(COOKIE)?.value ?? null;
}

/**
 * The caller's user id, read from the token's `sub` claim.
 *
 * Decoded without verifying the signature, deliberately. This is only used to decide what to
 * render — whether to show a "delete" button on a tweet, for instance. Every action that
 * depends on identity is re-authorised by the service that performs it, which does verify.
 * Verifying here would mean shipping a JWKS client into the frontend to duplicate a check the
 * gateway already makes on the same request.
 */
export async function currentUserId(): Promise<string | null> {
  const token = await sessionToken();
  if (!token) {
    return null;
  }
  const payload = token.split(".")[1];
  if (!payload) {
    return null;
  }
  try {
    const json = Buffer.from(payload, "base64url").toString("utf8");
    const claims = JSON.parse(json) as { sub?: string };
    return claims.sub ?? null;
  } catch {
    // A malformed cookie is a logged-out user, not a 500.
    return null;
  }
}
