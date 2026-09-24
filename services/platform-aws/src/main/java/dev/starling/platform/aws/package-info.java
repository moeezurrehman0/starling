/* SPDX-License-Identifier: MIT */

/**
 * Spring configuration for the AWS SDK, shared by every service.
 *
 * <p>The counterpart to {@code services:contracts}. That module holds the declarations and is
 * forbidden from containing Spring or configuration; this one holds exactly the wiring that rule
 * excludes. Nothing here describes a table or an item -- if a change belongs to the data model it
 * belongs in contracts, and if it belongs to how a client reaches AWS it belongs here.
 */
@NullMarked
package dev.starling.platform.aws;

import org.jspecify.annotations.NullMarked;
