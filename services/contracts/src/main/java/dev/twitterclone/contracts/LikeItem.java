/* SPDX-License-Identifier: MIT */
package dev.twitterclone.contracts;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A row of the {@code likes} table: one user's like of one tweet.
 *
 * <p>Keyed {@code (tweetId, userId)}, so the partition holds every like of a tweet. That makes "has
 * this user liked this tweet" a point read and "how many likes" a counter on {@link TweetItem}
 * rather than a count of this table — counting a partition is an O(n) scan and would be run on
 * every timeline render.
 *
 * <p>The item's existence is the whole payload; {@code likedAt} is kept only so the row can be
 * explained when it turns up in a stream record or a support query.
 */
public record LikeItem(String tweetId, String userId, Instant likedAt) {

  public static Builder builder() {
    return new Builder();
  }

  /** Mutable accumulator used by the enhanced client to rebuild an item from DynamoDB. */
  public static final class Builder {
    private @Nullable String tweetId;
    private @Nullable String userId;
    private @Nullable Instant likedAt;

    public Builder tweetId(String value) {
      this.tweetId = value;
      return this;
    }

    public Builder userId(String value) {
      this.userId = value;
      return this;
    }

    public Builder likedAt(Instant value) {
      this.likedAt = value;
      return this;
    }

    public LikeItem build() {
      return new LikeItem(
          Contracts.required(tweetId, "tweetId"),
          Contracts.required(userId, "userId"),
          Contracts.required(likedAt, "likedAt"));
    }
  }
}
