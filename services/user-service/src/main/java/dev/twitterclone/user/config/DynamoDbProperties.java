/* SPDX-License-Identifier: MIT */
package dev.twitterclone.user.config;

import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How this service reaches DynamoDB, and under what table names.
 *
 * @param endpoint override for the DynamoDB endpoint. Set to LocalStack in Tier L and left unset
 *     everywhere else, so that a deployed pod resolves the real regional endpoint from the SDK
 *     rather than from configuration somebody could get wrong.
 * @param tablePrefix prepended to every logical table name. Tier S and Tier P share an account in
 *     the sandbox story, so {@code sandbox-} and {@code prod-} prefixes are what keep a
 *     misconfigured pod from writing across environments. Empty locally, where the tables the
 *     Compose bootstrap creates are unprefixed.
 */
@ConfigurationProperties(prefix = "twitterclone.dynamodb")
public record DynamoDbProperties(@Nullable URI endpoint, String tablePrefix) {

  public DynamoDbProperties {
    tablePrefix = tablePrefix == null ? "" : tablePrefix;
  }

  /** Resolves a logical table name to the physical one this environment uses. */
  public String table(String logicalName) {
    return tablePrefix + logicalName;
  }
}
