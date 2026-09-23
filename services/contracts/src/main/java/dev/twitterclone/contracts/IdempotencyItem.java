/* SPDX-License-Identifier: MIT */
package dev.twitterclone.contracts;

import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A row of the {@code idempotency} table: the record that a given client request has already been
 * processed, and what it produced.
 *
 * <p>Write endpoints accept an {@code Idempotency-Key} header. The handler claims the key with a
 * conditional {@code PutItem}; if the claim fails, the stored outcome is replayed instead of the
 * operation being performed again. This matters more here than it would elsewhere, because posting
 * a tweet triggers fan-out to potentially thousands of timelines — a retried POST that is allowed
 * through does not merely duplicate a row, it duplicates the work.
 *
 * <p>{@code requestHash} is the hash of the <em>request</em>, and it is never overwritten. Storing
 * the response hash in the same field instead — the obvious shortcut — destroys the only evidence
 * that would let a later retry be recognised as the same request, so the second retry of a
 * succeeded call would be reported as a key conflict. The two hashes answer different questions and
 * cannot share a field.
 *
 * <p>The response body itself is not stored; {@code resultId} is. A replayed POST is answered by
 * re-reading the created resource, which keeps this table small and guarantees the replay reflects
 * the resource's current state rather than a stale snapshot of it.
 *
 * <p>Entries expire via TTL. The window has to exceed any plausible client retry schedule while
 * staying short enough that the table does not become a permanent log of every write.
 *
 * @param key the caller-namespaced idempotency key
 * @param requestHash SHA-256 of the request body that claimed the key
 * @param resultId the id of the resource the request created, absent until it completes
 * @param statusCode the status the original request returned, {@code 0} while still in flight
 * @param createdAt when the key was claimed
 * @param expiresAt unix epoch seconds at which TTL reclaims the row
 */
public record IdempotencyItem(
    String key,
    String requestHash,
    @Nullable String resultId,
    int statusCode,
    Instant createdAt,
    long expiresAt) {

  /** How long a claimed key is honoured before TTL reclaims it. */
  public static final Duration RETENTION = Duration.ofHours(24);

  /** {@link #statusCode} of a key that has been claimed but whose work has not finished. */
  public static final int IN_FLIGHT = 0;

  public static Builder builder() {
    return new Builder();
  }

  /** Whether the claiming request finished. */
  public boolean isComplete() {
    return statusCode != IN_FLIGHT;
  }

  /** Mutable accumulator used by the enhanced client to rebuild an item from DynamoDB. */
  public static final class Builder {
    private @Nullable String key;
    private @Nullable String requestHash;
    private @Nullable String resultId;
    private int statusCode;
    private @Nullable Instant createdAt;
    private long expiresAt;

    public Builder key(String value) {
      this.key = value;
      return this;
    }

    public Builder requestHash(String value) {
      this.requestHash = value;
      return this;
    }

    public Builder resultId(@Nullable String value) {
      this.resultId = value;
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
          Contracts.required(requestHash, "requestHash"),
          resultId,
          statusCode,
          Contracts.required(createdAt, "createdAt"),
          expiresAt);
    }
  }
}
