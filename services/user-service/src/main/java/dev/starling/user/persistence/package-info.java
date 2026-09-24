/* SPDX-License-Identifier: MIT */
/**
 * DynamoDB access for user-service.
 *
 * <p>These classes are the only place that knows a table name or an attribute abbreviation. The
 * domain layer above them deals in user ids and handles.
 */
@NullMarked
package dev.starling.user.persistence;

import org.jspecify.annotations.NullMarked;
