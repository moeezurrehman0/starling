/* SPDX-License-Identifier: MIT */

/**
 * Getting a request to the service that owns it.
 *
 * <p>A hand-written proxy over {@code RestClient} rather than Spring Cloud Gateway: the edge needs
 * three behaviours, and a second web stack with its own release train is a poor trade for them.
 */
@NullMarked
package dev.twitterclone.gateway.routing;

import org.jspecify.annotations.NullMarked;
