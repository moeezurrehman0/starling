/* SPDX-License-Identifier: MIT */
package dev.twitterclone.contracts;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A row of the {@code timelines} table: one materialised entry in one user's home timeline.
 *
 * <p>Keyed {@code (ownerId, tweetId)}. Because {@code tweetId} is a UUIDv7 its lexicographic order
 * is creation order, so a descending query on the sort key returns a timeline page directly, with
 * no sort and no secondary index.
 *
 * <p>Written only by {@code fanout-worker} and read only by {@code timeline-service}. That split is
 * the reason this record is shared rather than duplicated: the two services never exchange a
 * message about this table, so a disagreement about an attribute name would surface as an empty
 * timeline rather than as an error.
 *
 * <p>{@code expiresAt} drives DynamoDB TTL. Materialised timelines are a cache, not a record of
 * anything — the tweets themselves are durable — so entries are allowed to expire after seven days
 * rather than accumulating for every user forever. TTL deletion is asynchronous and AWS offers no
 * latency guarantee, so reads must tolerate an entry that is past its expiry but not yet removed;
 * they do, because such an entry is merely an old tweet.
 */
public record TimelineEntryItem(
    String ownerId, String tweetId, String authorId, Instant createdAt, long expiresAt) {

  /** How long a materialised entry survives before TTL reclaims it. */
  public static final java.time.Duration RETENTION = java.time.Duration.ofDays(7);

  public static Builder builder() {
    return new Builder();
  }

  /** Mutable accumulator used by the enhanced client to rebuild an item from DynamoDB. */
  public static final class Builder {
    private @Nullable String ownerId;
    private @Nullable String tweetId;
    private @Nullable String authorId;
    private @Nullable Instant createdAt;
    private long expiresAt;

    public Builder ownerId(String value) {
      this.ownerId = value;
      return this;
    }

    public Builder tweetId(String value) {
      this.tweetId = value;
      return this;
    }

    public Builder authorId(String value) {
      this.authorId = value;
      return this;
    }

    public Builder createdAt(Instant value) {
      this.createdAt = value;
      return this;
    }

    /** Unix epoch seconds, which is the only form DynamoDB TTL accepts. */
    public Builder expiresAt(long value) {
      this.expiresAt = value;
      return this;
    }

    public TimelineEntryItem build() {
      return new TimelineEntryItem(
          Contracts.required(ownerId, "ownerId"),
          Contracts.required(tweetId, "tweetId"),
          Contracts.required(authorId, "authorId"),
          Contracts.required(createdAt, "createdAt"),
          expiresAt);
    }
  }
}
