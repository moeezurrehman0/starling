/* SPDX-License-Identifier: MIT */
package dev.twitterclone.fanout.config;

import dev.twitterclone.contracts.StreamCheckpointItem;
import dev.twitterclone.contracts.TableSchemas;
import dev.twitterclone.contracts.TimelineEntryItem;
import dev.twitterclone.contracts.UserItem;
import dev.twitterclone.platform.aws.DynamoDbConfig;
import dev.twitterclone.platform.aws.DynamoDbProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient;

/**
 * Tables and the streams client.
 *
 * <p>{@link DynamoDbStreamsClient} is a separate client from {@code DynamoDbClient} because the
 * Streams API is a separate service endpoint, not a set of extra operations on the data plane.
 */
@Configuration(proxyBeanMethods = false)
@Import(DynamoDbConfig.class)
@EnableConfigurationProperties(FanoutProperties.class)
public class DynamoDbTablesConfig {

  @Bean
  public DynamoDbTable<TimelineEntryItem> timelinesTable(
      DynamoDbEnhancedClient enhanced, DynamoDbProperties properties) {
    return enhanced.table(properties.table("timelines"), TableSchemas.TIMELINE_ENTRY);
  }

  @Bean
  public DynamoDbTable<UserItem> usersTable(
      DynamoDbEnhancedClient enhanced, DynamoDbProperties properties) {
    return enhanced.table(properties.table("users"), TableSchemas.USER);
  }

  @Bean
  public DynamoDbTable<StreamCheckpointItem> checkpointsTable(
      DynamoDbEnhancedClient enhanced, DynamoDbProperties properties) {
    return enhanced.table(properties.table("stream_checkpoints"), TableSchemas.STREAM_CHECKPOINT);
  }
}
