/* SPDX-License-Identifier: MIT */
package dev.twitterclone.tweet.persistence;

import dev.twitterclone.contracts.IdempotencyItem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * Claims {@code Idempotency-Key} values so a retried write is not performed twice.
 *
 * <p>This matters more here than it would for most endpoints. A duplicated POST does not merely
 * insert a second row: posting a tweet triggers fan-out to every follower, so a retry that gets
 * through multiplies the work by the size of an audience rather than by one.
 *
 * <p>The key is namespaced by caller. Without that, two users who both send {@code Idempotency-Key:
 * 1} would collide, and the second would be handed the first's result — a client-chosen string is
 * not a global identifier and must never be treated as one.
 */
@Repository
public class IdempotencyRepository {

  private final DynamoDbTable<IdempotencyItem> keys;

  public IdempotencyRepository(DynamoDbTable<IdempotencyItem> idempotencyTable) {
    this.keys = idempotencyTable;
  }

  /**
   * Attempts to claim a key.
   *
   * @param userId the caller, which namespaces the key
   * @param idempotencyKey the client-supplied value
   * @param requestBody the body being submitted, hashed to detect key reuse
   * @return {@link Claim#fresh()} when this call won the claim, or the previous outcome
   */
  public Claim claim(String userId, String idempotencyKey, String requestBody) {
    String namespaced = namespace(userId, idempotencyKey);
    String hash = sha256(requestBody);
    Instant now = Instant.now();

    try {
      keys.putItem(
          PutItemEnhancedRequest.builder(IdempotencyItem.class)
              .item(
                  IdempotencyItem.builder()
                      .key(namespaced)
                      .requestHash(hash)
                      // Claimed before the work runs, not after. Recording the outcome first
                      // would leave the window this exists to close; the cost is that a
                      // crash mid-request burns the key until TTL reclaims it, which is the
                      // safe direction to fail for an operation that fans out.
                      .statusCode(IdempotencyItem.IN_FLIGHT)
                      .createdAt(now)
                      .expiresAt(now.plus(IdempotencyItem.RETENTION).getEpochSecond())
                      .build())
              .conditionExpression(
                  Expression.builder().expression("attribute_not_exists(k)").build())
              .build());
      return Claim.fresh();
    } catch (ConditionalCheckFailedException e) {
      IdempotencyItem existing = keys.getItem(Key.builder().partitionValue(namespaced).build());
      if (existing == null) {
        // TTL removed the row between the failed put and this read. Treating that as fresh
        // is right: the retry window has expired, so this is a new request by definition.
        return Claim.fresh();
      }
      if (!existing.requestHash().equals(hash)) {
        // Same key, different body. That is a client bug, and honouring it would replay a
        // result for a request that was never made.
        return Claim.mismatched();
      }
      return Claim.replayed(existing);
    }
  }

  /**
   * Records what a claimed key produced, so a later retry can be answered without repeating it.
   *
   * @param userId the caller
   * @param idempotencyKey the claimed value
   * @param statusCode the status the original request returned
   * @param resultId the id of the resource it created, if any
   */
  public void complete(
      String userId, String idempotencyKey, int statusCode, @Nullable String resultId) {
    String namespaced = namespace(userId, idempotencyKey);
    IdempotencyItem existing = keys.getItem(Key.builder().partitionValue(namespaced).build());
    if (existing == null) {
      // TTL reclaimed the key while the work was running. Recreating it here would resurrect
      // a row whose retention window has already elapsed, so the completion is dropped: the
      // worst case is that a very late retry does the work twice, which is exactly the state
      // the client would be in had it never sent a key.
      return;
    }
    keys.putItem(
        IdempotencyItem.builder()
            .key(namespaced)
            // Carried forward unchanged. This is the request hash, and overwriting it with
            // anything derived from the response would break the very next retry's
            // same-request check.
            .requestHash(existing.requestHash())
            .resultId(resultId)
            .statusCode(statusCode)
            .createdAt(existing.createdAt())
            // The original expiry is kept rather than extended. Refreshing it here would let
            // a client hold a key alive indefinitely by retrying just before it lapsed.
            .expiresAt(existing.expiresAt())
            .build());
  }

  /**
   * Releases a key whose work failed, so the client's retry is not answered with a permanent
   * in-flight record.
   *
   * <p>Only safe because it is called when the operation is known <em>not</em> to have taken
   * effect. Releasing a key after a write that may have landed would reopen the duplicate window
   * this class exists to close.
   *
   * @param userId the caller
   * @param idempotencyKey the claimed value
   */
  public void release(String userId, String idempotencyKey) {
    keys.deleteItem(Key.builder().partitionValue(namespace(userId, idempotencyKey)).build());
  }

  /**
   * Prefixes a client-chosen key with the caller's id.
   *
   * <p>The separator is a character the id cannot contain — user ids are UUIDs — so no pair of
   * (user, key) can be ambiguous with another.
   */
  private static String namespace(String userId, String idempotencyKey) {
    return userId + "#" + idempotencyKey;
  }

  /** SHA-256 of a request body, hex-encoded. */
  public static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required of every JVM", e);
    }
  }

  /**
   * What claiming a key produced.
   *
   * @param outcome whether the caller may proceed
   * @param previous the stored record, present only when replaying
   */
  public record Claim(Outcome outcome, Optional<IdempotencyItem> previous) {

    /** The three ways a claim can end. */
    public enum Outcome {
      /** The caller won the key and should do the work. */
      FRESH,
      /** The key was already claimed by an identical request. */
      REPLAYED,
      /** The key was already claimed by a <em>different</em> request. */
      MISMATCHED
    }

    static Claim fresh() {
      return new Claim(Outcome.FRESH, Optional.empty());
    }

    static Claim mismatched() {
      return new Claim(Outcome.MISMATCHED, Optional.empty());
    }

    static Claim replayed(IdempotencyItem previous) {
      return new Claim(Outcome.REPLAYED, Optional.of(previous));
    }

    /** Whether the caller should perform the operation. */
    public boolean proceed() {
      return outcome == Outcome.FRESH;
    }
  }
}
