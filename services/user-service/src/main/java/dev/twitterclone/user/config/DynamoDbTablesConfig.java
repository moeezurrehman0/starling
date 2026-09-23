/* SPDX-License-Identifier: MIT */
package dev.twitterclone.user.config;

import dev.twitterclone.contracts.FollowItem;
import dev.twitterclone.contracts.HandleItem;
import dev.twitterclone.contracts.TableSchemas;
import dev.twitterclone.contracts.UserItem;
import dev.twitterclone.platform.aws.DynamoDbConfig;
import dev.twitterclone.platform.aws.DynamoDbProperties;
import dev.twitterclone.user.persistence.CredentialItem;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

/**
 * The four tables this service reads or writes.
 *
 * <p>The client itself comes from {@code services:platform-aws}; only the table bindings are
 * per-service, because only the service that owns a table knows which schema describes it.
 */
@Configuration(proxyBeanMethods = false)
@Import(DynamoDbConfig.class)
public class DynamoDbTablesConfig {

  @Bean
  public DynamoDbTable<UserItem> usersTable(
      DynamoDbEnhancedClient enhanced, DynamoDbProperties properties) {
    return enhanced.table(properties.table("users"), TableSchemas.USER);
  }

  @Bean
  public DynamoDbTable<HandleItem> handlesTable(
      DynamoDbEnhancedClient enhanced, DynamoDbProperties properties) {
    return enhanced.table(properties.table("handles"), TableSchemas.HANDLE);
  }

  @Bean
  public DynamoDbTable<CredentialItem> credentialsTable(
      DynamoDbEnhancedClient enhanced, DynamoDbProperties properties) {
    return enhanced.table(properties.table("credentials"), CredentialItem.SCHEMA);
  }

  @Bean
  public DynamoDbTable<FollowItem> followsTable(
      DynamoDbEnhancedClient enhanced, DynamoDbProperties properties) {
    return enhanced.table(properties.table("follows"), TableSchemas.FOLLOW);
  }
}
