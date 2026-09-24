/* SPDX-License-Identifier: MIT */

/**
 * Wiring for the fan-out worker.
 *
 * <p>Notably a second DynamoDB Streams client with longer timeouts than the shared one in {@code
 * platform-aws}: {@code GetRecords} against an idle shard is slow precisely because it found
 * nothing, and the shared two-second attempt timeout would abort healthy polls.
 */
@NullMarked
package dev.starling.fanout.config;

import org.jspecify.annotations.NullMarked;
