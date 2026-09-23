/* SPDX-License-Identifier: MIT */
/**
 * Shared DynamoDB contracts: the item records and table schemas for every table that more than one
 * service touches.
 *
 * <h2>Why this module exists</h2>
 *
 * <p>The approved repository layout had no shared module, and that was correct while each service
 * owned a private PostgreSQL schema — there was nothing to share. Moving the operational store to
 * DynamoDB (ADR-0011) changed the shape of the problem: {@code tweets} is written by {@code
 * tweet-service} and read by {@code fanout-worker}, and {@code timelines} is written by {@code
 * fanout-worker} and read by {@code timeline-service}. Two independently declared {@code
 * TableSchema}s over one physical table can disagree about an attribute name, and DynamoDB will not
 * complain: the writer simply stores an attribute the reader never looks at, and the reader sees
 * null. That is a silent data-loss bug, and it is the kind that survives every unit test on both
 * sides.
 *
 * <p>So this module holds the declarations and nothing else. It has no Spring dependency, no
 * client, no repository and no behaviour, which keeps it from becoming the shared-utility dumping
 * ground that makes monorepo modules regrettable. If a class here ever needs a {@code
 * DynamoDbClient}, it belongs in a service instead.
 *
 * <h2>Why the schemas are hand-written</h2>
 *
 * <p>Every schema is a {@link software.amazon.awssdk.enhanced.dynamodb.mapper.StaticTableSchema}
 * built explicitly, rather than {@code TableSchema.fromBean} or an annotated immutable class. The
 * plan called for this on the grounds of clarity. Phase 3 supplied a second and stronger reason:
 * the runtime image is a jlink-trimmed JDK, and the bean and annotation paths resolve attributes
 * reflectively at first use. A reflective mapper failure surfaces as a runtime error on whichever
 * code path happens to touch that table first, which is precisely the failure mode the module
 * allow-list work was about eliminating. Explicit getters and setters are checked by the compiler
 * instead.
 *
 * <h2>Attribute naming</h2>
 *
 * <p>Attribute names are short and fixed. DynamoDB bills on item size and every attribute name is
 * stored on every item, so a descriptive Java field maps to a terse stored name. The mapping is
 * declared once, here, and the round-trip tests assert the stored names explicitly so that a rename
 * cannot happen by accident — under expand–contract an attribute name is as much a published
 * contract as a REST field.
 */
@NullMarked
package dev.twitterclone.contracts;

import org.jspecify.annotations.NullMarked;
