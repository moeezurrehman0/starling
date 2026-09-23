/* SPDX-License-Identifier: MIT */
package dev.twitterclone.user.persistence;

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.mapper.StaticAttributeTags;
import software.amazon.awssdk.enhanced.dynamodb.mapper.StaticImmutableTableSchema;

/**
 * A row of the {@code credentials} table: one account's password hash.
 *
 * <p>Defined here rather than in {@code services/contracts} on purpose. Everything in that module
 * is visible to every service that depends on it, and tweet-service and timeline-service both read
 * {@code UserItem} to render an author. A hash living on that record would be handed to two
 * services that have no use for it, and the only thing stopping them logging or serialising it
 * would be that nobody thought to. Keeping the credential in a separate table with a separate
 * record means they cannot read it by accident, only by adding a dependency somebody would have to
 * review.
 *
 * <p>Keyed by the same normalised handle as {@code handles}, so login is one extra point read and
 * nothing else changes.
 *
 * @param handle lower-cased handle, matching the {@code handles} table
 * @param passwordHash a bcrypt hash; never a raw password and never reversible
 * @param updatedAt when the hash was last set, for password-age reporting
 */
public record CredentialItem(String handle, String passwordHash, Instant updatedAt) {

  /** Stored attribute names, abbreviated like every other table in this system. */
  public static final TableSchema<CredentialItem> SCHEMA =
      StaticImmutableTableSchema.builder(CredentialItem.class, Builder.class)
          .newItemBuilder(Builder::new, Builder::build)
          .addAttribute(
              String.class,
              a ->
                  a.name("h")
                      .getter(CredentialItem::handle)
                      .setter(Builder::handle)
                      .addTag(StaticAttributeTags.primaryPartitionKey()))
          .addAttribute(
              String.class,
              a -> a.name("ph").getter(CredentialItem::passwordHash).setter(Builder::passwordHash))
          .addAttribute(
              Instant.class,
              a -> a.name("ua").getter(CredentialItem::updatedAt).setter(Builder::updatedAt))
          .build();

  public static Builder builder() {
    return new Builder();
  }

  /** Mutable accumulator used by the enhanced client to rebuild an item from DynamoDB. */
  public static final class Builder {
    private @Nullable String handle;
    private @Nullable String passwordHash;
    private @Nullable Instant updatedAt;

    public Builder handle(String value) {
      this.handle = value;
      return this;
    }

    public Builder passwordHash(String value) {
      this.passwordHash = value;
      return this;
    }

    public Builder updatedAt(Instant value) {
      this.updatedAt = value;
      return this;
    }

    /**
     * Builds the item.
     *
     * @throws IllegalStateException naming the attribute, if one is missing
     */
    public CredentialItem build() {
      return new CredentialItem(
          require(handle, "handle"),
          require(passwordHash, "passwordHash"),
          require(updatedAt, "updatedAt"));
    }

    private static <T> T require(@Nullable T value, String attribute) {
      if (value == null) {
        throw new IllegalStateException("credentials." + attribute + " is required");
      }
      return value;
    }
  }

  /** Never let a hash reach a log line through a default record toString. */
  @Override
  public String toString() {
    return "CredentialItem[handle=" + handle + ", passwordHash=***, updatedAt=" + updatedAt + "]";
  }
}
