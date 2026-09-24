/* SPDX-License-Identifier: MIT */

/**
 * The Redis token bucket applied at the edge.
 *
 * <p>Atomic in Lua, because a read-modify-write across pods enforces a limit just tightly enough
 * that nobody checks it. Fails open, because a rate limiter that can take the site down has
 * inverted its own purpose.
 */
@NullMarked
package dev.starling.gateway.ratelimit;

import org.jspecify.annotations.NullMarked;
