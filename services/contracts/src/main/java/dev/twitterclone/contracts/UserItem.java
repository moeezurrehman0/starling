/* SPDX-License-Identifier: MIT */
package dev.twitterclone.contracts;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A row of the {@code users} table: the profile and follow-graph counters for one account.
 *
 * <p>Partitioned on {@code userId}, a UUIDv7. Handle lookup goes through {@link HandleItem} rather
 * than a secondary index on this table, because a handle must be globally unique and only a
 * conditional write against a table whose partition key <em>is</em> the handle can enforce that.
 *
 * <p>{@code celebrity} is stored rather than derived at read time, even though it is a pure
 * function of {@code followerCount}. Timeline reads consult it on every request to choose between
 * the two caches (ADR-0013), and recomputing a threshold is cheaper than the alternative only until
 * you need the decision to be stable: a user hovering at the boundary would otherwise flip cache
 * tiers between two reads of the same timeline. The flag is updated by {@code user-service} when a
 * follow crosses the threshold, which makes the transition an explicit, observable event rather
 * than an emergent one.
 */
public record UserItem(
    String userId,
    String handle,
    String displayName,
    @Nullable String bio,
    @Nullable String avatarUrl,
    Instant createdAt,
    long followerCount,
    boolean celebrity) {

  /** Follower count at or above which a user is served from the celebrity cache. */
  public static final long CELEBRITY_THRESHOLD = 10_000L;

  public static Builder builder() {
    return new Builder();
  }

  /** Mutable accumulator used by the enhanced client to rebuild an item from DynamoDB. */
  public static final class Builder {
    private @Nullable String userId;
    private @Nullable String handle;
    private @Nullable String displayName;
    private @Nullable String bio;
    private @Nullable String avatarUrl;
    private @Nullable Instant createdAt;
    private long followerCount;
    private boolean celebrity;

    public Builder userId(String value) {
      this.userId = value;
      return this;
    }

    public Builder handle(String value) {
      this.handle = value;
      return this;
    }

    public Builder displayName(String value) {
      this.displayName = value;
      return this;
    }

    public Builder bio(@Nullable String value) {
      this.bio = value;
      return this;
    }

    public Builder avatarUrl(@Nullable String value) {
      this.avatarUrl = value;
      return this;
    }

    public Builder createdAt(Instant value) {
      this.createdAt = value;
      return this;
    }

    public Builder followerCount(long value) {
      this.followerCount = value;
      return this;
    }

    public Builder celebrity(boolean value) {
      this.celebrity = value;
      return this;
    }

    public UserItem build() {
      return new UserItem(
          Contracts.required(userId, "userId"),
          Contracts.required(handle, "handle"),
          Contracts.required(displayName, "displayName"),
          bio,
          avatarUrl,
          Contracts.required(createdAt, "createdAt"),
          followerCount,
          celebrity);
    }
  }
}
