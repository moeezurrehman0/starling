/* SPDX-License-Identifier: MIT */

/**
 * Wiring for the edge.
 *
 * <p>Three things the gateway must get right before it does anything useful: upstream timeouts, so
 * one slow service cannot exhaust the shared request threads; an authentication policy that is a
 * convenience rather than the security boundary; and a route table that is legible from the
 * deployment manifest.
 */
@NullMarked
package dev.twitterclone.gateway.config;

import org.jspecify.annotations.NullMarked;
