/* SPDX-License-Identifier: MIT */
package dev.starling.timeline.domain;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A tweet as this service sees it: whatever tweet-service returned, passed through.
 *
 * <p>Deliberately a copy of tweet-service's response shape rather than a shared type. The two
 * services are separately deployable, and a shared wire record would mean adding a field to one
 * response is a coordinated release. Unknown fields are ignored on the way in, so tweet-service can
 * add to its payload without this service noticing.
 *
 * @param id the tweet id, a UUIDv7 and therefore sortable by time
 * @param authorId who posted it
 * @param text the body
 * @param mediaKeys attached object keys
 * @param replyTo parent tweet, if a reply
 * @param retweetOf source tweet, if a retweet
 * @param createdAt when it was posted
 * @param likeCount how many likes it has
 */
public record TweetView(
    String id,
    String authorId,
    String text,
    List<String> mediaKeys,
    @Nullable String replyTo,
    @Nullable String retweetOf,
    Instant createdAt,
    long likeCount) {}
