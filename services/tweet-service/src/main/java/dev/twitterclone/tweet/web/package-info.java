/* SPDX-License-Identifier: MIT */

/**
 * The HTTP surface: request and response records, the controller, and the error mapping.
 *
 * <p>The wire records here are deliberately not the DynamoDB item records. Those carry short
 * attribute names because every one of them is stored on every row; these carry readable ones. The
 * projection between them is the seam that lets storage change without breaking clients.
 */
@NullMarked
package dev.twitterclone.tweet.web;

import org.jspecify.annotations.NullMarked;
