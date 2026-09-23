/* SPDX-License-Identifier: MIT */
package dev.twitterclone.contracts;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A row of the {@code idempotency} table: the record that a given client request has already been
 * processed, and what it returned.
 *
 * <p>Write endpoints accept an {@code Idempotency-Key} header. The handler claims the key with a
 * conditional {@code PutItem}; if the claim fails, the stored response is replayed instead of the
 * operation being performed again. This matters more here than it would elsewhere, because posting
 * a tweet triggers fan-out to potentially thousands of timelines — a retried POST that is allowed
 * through does not merely duplicate a row, it duplicates the work.
 *
 * <p>{@code responseHash} is stored rather than the response body. The body can be large and is not
 * needed: the hash is enough to detect a client reusing one key for two different requests, which
 * is a client bug worth reporting as a conflict rather than silently honouring.
 *
 * <p>Entries expire via TTL. The window has to exceed any plausible client retry schedule while
 * staying short enough that the table does not become a permanent log of every write.
 */
public record IdempotencyItem(
    String key, String responseHash, int statusCode, Instant createdAt, long expiresAt) {

  /** How long a claimed key is honoured before TTL reclaims it. */
  public static final java.time.Duration RETENTION = java.time.Duration.ofHours(24);

  public static Builder builder() {
    return new Builder();
  }

  /** Mutable accumulator used by the enhanced client to rebuild an item from DynamoDB. */
  public static final class Builder {
    private @Nullable String key;
    private @Nullable String responseHash;
    private int statusCode;
    private @Nullable Instant createdAt;
    private long expiresAt;

    public Builder key(String value) {
      this.key = value;
      return this;
    }

    public Builder responseHash(String value) {
      this.responseHash = value;
      return this;
    }

    public Builder statusCode(int value) {
      this.statusCode = value;
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

    public IdempotencyItem build() {
      return new IdempotencyItem(
          Contracts.required(key, "key"),
          Contracts.required(responseHash, "responseHash"),
          statusCode,
          Contracts.required(createdAt, "createdAt"),
          expiresAt);
    }
  }
}
