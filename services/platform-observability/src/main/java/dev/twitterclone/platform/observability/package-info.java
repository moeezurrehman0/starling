/* SPDX-License-Identifier: MIT */

/**
 * Observability wiring shared by every HTTP service: currently the access log.
 *
 * <p>Separate from {@code platform-aws} because nothing here is AWS-specific, and a web service
 * that never touches DynamoDB should not pull the SDK in to get one log line.
 */
@NullMarked
package dev.twitterclone.platform.observability;

import org.jspecify.annotations.NullMarked;
