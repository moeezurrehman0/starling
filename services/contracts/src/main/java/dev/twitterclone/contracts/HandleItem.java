/* SPDX-License-Identifier: MIT */
package dev.twitterclone.contracts;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A row of the {@code handles} table: the reservation of one {@code @handle} by one user.
 *
 * <p>This table exists only to make handle uniqueness enforceable. DynamoDB can guarantee
 * uniqueness on a partition key and nowhere else, so the handle is the partition key here and a
 * claim is a {@code PutItem} with {@code attribute_not_exists(h)}. A global secondary index on
 * {@link UserItem} could answer "who owns this handle" just as well, but GSIs are eventually
 * consistent and carry no conditional-write semantics, so two concurrent signups for the same
 * handle would both succeed.
 *
 * <p>The handle is stored lower-cased. Case-preserving display is the caller's business; uniqueness
 * is case-insensitive, and folding at the boundary is the only way to make that true of the
 * partition key itself.
 */
public record HandleItem(String handle, String userId, Instant claimedAt) {

  public static Builder builder() {
    return new Builder();
  }

  /** Mutable accumulator used by the enhanced client to rebuild an item from DynamoDB. */
  public static final class Builder {
    private @Nullable String handle;
    private @Nullable String userId;
    private @Nullable Instant claimedAt;

    public Builder handle(String value) {
      this.handle = value;
      return this;
    }

    public Builder userId(String value) {
      this.userId = value;
      return this;
    }

    public Builder claimedAt(Instant value) {
      this.claimedAt = value;
      return this;
    }

    public HandleItem build() {
      return new HandleItem(
          Contracts.required(handle, "handle"),
          Contracts.required(userId, "userId"),
          Contracts.required(claimedAt, "claimedAt"));
    }
  }
}
