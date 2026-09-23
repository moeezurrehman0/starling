/* SPDX-License-Identifier: MIT */
package dev.twitterclone.contracts;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A row of the {@code follows} table: one directed edge of the follow graph.
 *
 * <p>Keyed {@code (followerId, followeeId)}, which answers "who do I follow" — the read the
 * timeline path needs — as a single partition query. The reverse question, "who follows this
 * author", is what fan-out needs, and it is served by the {@code followee-index} GSI.
 *
 * <p>The asymmetry is deliberate and is the main cost of this design. A celebrity's follower list
 * is one enormous partition in that index, and fan-out must page through it. That is precisely why
 * authors above {@link UserItem#CELEBRITY_THRESHOLD} followers are not fanned out at all but merged
 * at read time instead: the index is unusable at that size, so the design avoids using it rather
 * than pretending it scales.
 */
public record FollowItem(String followerId, String followeeId, Instant createdAt) {

  /** Name of the GSI that answers "the accounts following this user". */
  public static final String FOLLOWEE_INDEX = "followee-index";

  public static Builder builder() {
    return new Builder();
  }

  /** Mutable accumulator used by the enhanced client to rebuild an item from DynamoDB. */
  public static final class Builder {
    private @Nullable String followerId;
    private @Nullable String followeeId;
    private @Nullable Instant createdAt;

    public Builder followerId(String value) {
      this.followerId = value;
      return this;
    }

    public Builder followeeId(String value) {
      this.followeeId = value;
      return this;
    }

    public Builder createdAt(Instant value) {
      this.createdAt = value;
      return this;
    }

    public FollowItem build() {
      return new FollowItem(
          Contracts.required(followerId, "followerId"),
          Contracts.required(followeeId, "followeeId"),
          Contracts.required(createdAt, "createdAt"));
    }
  }
}
