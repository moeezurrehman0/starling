/* SPDX-License-Identifier: MIT */
package dev.twitterclone.user.persistence;

import dev.twitterclone.contracts.HandleItem;
import dev.twitterclone.contracts.UserItem;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactPutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactWriteItemsEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/** Reads and writes the {@code users} and {@code handles} tables. */
@Repository
public class UserRepository {

  private final DynamoDbEnhancedClient enhanced;
  private final DynamoDbTable<UserItem> users;
  private final DynamoDbTable<HandleItem> handles;
  private final DynamoDbTable<CredentialItem> credentials;

  public UserRepository(
      DynamoDbEnhancedClient enhanced,
      DynamoDbTable<UserItem> usersTable,
      DynamoDbTable<HandleItem> handlesTable,
      DynamoDbTable<CredentialItem> credentialsTable) {
    this.enhanced = enhanced;
    this.users = usersTable;
    this.handles = handlesTable;
    this.credentials = credentialsTable;
  }

  public Optional<UserItem> findById(String userId) {
    return Optional.ofNullable(users.getItem(Key.builder().partitionValue(userId).build()));
  }

  /** Resolves a handle to its owner, or empty if nobody has claimed it. */
  public Optional<UserItem> findByHandle(String handle) {
    // Two point reads rather than a GSI on users. The handle is immutable once claimed, so
    // this is a tiny strongly-consistent lookup followed by another, where a GSI would be
    // eventually consistent and could fail to find a user who registered a moment ago --
    // during login, which is the one moment a user is certain they exist.
    return Optional.ofNullable(
            handles.getItem(Key.builder().partitionValue(normalise(handle)).build()))
        .flatMap(claimed -> findById(claimed.userId()));
  }

  /**
   * Creates a user, claims their handle and stores their password hash, or fails if the handle is
   * taken.
   *
   * <p>All three writes go in one transaction, conditional on the handle not already existing. That
   * condition <em>is</em> the uniqueness constraint. Checking first and writing second leaves a
   * window in which two registrations both see the handle as free, and DynamoDB has no unique index
   * to fall back on when they do. Putting the credential in the same transaction also rules out the
   * half-created account -- a user row with no way to log in, which looks like a registered account
   * to everything except the person trying to use it.
   *
   * @throws HandleAlreadyTakenException if another account already holds the handle
   */
  public UserItem createWithHandle(UserItem user, String passwordHash) {
    String handle = normalise(user.handle());
    HandleItem claim =
        HandleItem.builder().handle(handle).userId(user.userId()).claimedAt(Instant.now()).build();
    CredentialItem credential =
        CredentialItem.builder()
            .handle(handle)
            .passwordHash(passwordHash)
            .updatedAt(Instant.now())
            .build();

    try {
      enhanced.transactWriteItems(
          TransactWriteItemsEnhancedRequest.builder()
              .addPutItem(
                  handles,
                  TransactPutItemEnhancedRequest.builder(HandleItem.class)
                      .item(claim)
                      .conditionExpression(
                          Expression.builder().expression("attribute_not_exists(h)").build())
                      .build())
              .addPutItem(users, user)
              .addPutItem(credentials, credential)
              .build());
      return user;
    } catch (TransactionCanceledException e) {
      // A cancelled transaction reports a reason per item. Anything other than the handle's
      // conditional check is a real fault, and reporting it as a duplicate handle would send
      // the caller off renaming their account to work around an outage.
      boolean handleTaken =
          e.cancellationReasons().stream()
              .anyMatch(reason -> "ConditionalCheckFailed".equals(reason.code()));
      if (handleTaken) {
        throw new HandleAlreadyTakenException(user.handle(), e);
      }
      throw e;
    }
  }

  /**
   * Adds {@code delta} to a user's follower count and returns the updated item.
   *
   * <p>An atomic {@code ADD}, not a read-modify-write. Reading the count and putting back value+1
   * loses increments under concurrency, and a celebrity gaining followers is exactly the concurrent
   * case. The count is also what decides celebrity status, so a lost increment would mean an
   * account silently never crossing the threshold.
   */
  public UserItem adjustFollowerCount(String userId, long delta) {
    Map<String, AttributeValue> updated =
        enhanced
            .dynamoDbClient()
            .updateItem(
                request ->
                    request
                        .tableName(users.tableName())
                        .key(Map.of("uid", AttributeValue.fromS(userId)))
                        .updateExpression("ADD fc :d")
                        .conditionExpression("attribute_exists(uid)")
                        .expressionAttributeValues(
                            Map.of(":d", AttributeValue.fromN(Long.toString(delta))))
                        .returnValues(ReturnValue.ALL_NEW))
            .attributes();
    return users.tableSchema().mapToItem(updated);
  }

  /**
   * Sets the stored celebrity flag, but only if it currently holds the opposite value.
   *
   * <p>The condition makes the transition both idempotent and single-winner: exactly one caller
   * flips the flag, so that caller can act on the transition without the action happening twice.
   * Returning {@code false} means somebody else got there first, which is not an error.
   */
  public boolean setCelebrity(String userId, boolean celebrity) {
    try {
      enhanced
          .dynamoDbClient()
          .updateItem(
              request ->
                  request
                      .tableName(users.tableName())
                      .key(Map.of("uid", AttributeValue.fromS(userId)))
                      .updateExpression("SET celeb = :new")
                      .conditionExpression("attribute_exists(uid) AND celeb = :old")
                      .expressionAttributeValues(
                          Map.of(
                              ":new", AttributeValue.fromBool(celebrity),
                              ":old", AttributeValue.fromBool(!celebrity))));
      return true;
    } catch (ConditionalCheckFailedException e) {
      return false;
    }
  }

  /** The stored password hash for a handle, or empty if nobody holds it. */
  public Optional<String> passwordHash(String handle) {
    return Optional.ofNullable(
            credentials.getItem(Key.builder().partitionValue(normalise(handle)).build()))
        .map(CredentialItem::passwordHash);
  }

  /** Handles compare case-insensitively, so the stored key is the lower-cased form. */
  public static String normalise(String handle) {
    return handle.toLowerCase(Locale.ROOT);
  }
}
