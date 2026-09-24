/* SPDX-License-Identifier: MIT */
package dev.starling.user.config;

import dev.starling.contracts.FollowItem;
import dev.starling.contracts.HandleItem;
import dev.starling.contracts.TableSchemas;
import dev.starling.contracts.UserItem;
import dev.starling.platform.aws.DynamoDbConfig;
import dev.starling.platform.aws.DynamoDbProperties;
import dev.starling.user.persistence.CredentialItem;
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
