/* SPDX-License-Identifier: MIT */

/**
 * Making a single request findable afterwards.
 *
 * <p>Separate from tracing on purpose: a trace is sampled, whereas the id in the response header is
 * always there, which is what makes a bug report answerable without a reproduction.
 */
@NullMarked
package dev.twitterclone.gateway.observability;

import org.jspecify.annotations.NullMarked;
