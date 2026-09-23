/* SPDX-License-Identifier: MIT */

/**
 * The HTTP surface: request and response bodies, controllers and error mapping.
 *
 * <p>Nothing in here knows what a DynamoDB table is and nothing below it knows what a status code
 * is. The rule is one-directional -- this package may depend on {@code domain}, and {@code domain}
 * may not depend on this one -- which is what keeps a change to the wire format from being a change
 * to how an account is stored.
 */
@NullMarked
package dev.twitterclone.user.web;

import org.jspecify.annotations.NullMarked;
