/* SPDX-License-Identifier: MIT */
package dev.twitterclone.tweet.config;

import dev.twitterclone.contracts.IdempotencyItem;
import dev.twitterclone.contracts.LikeItem;
import dev.twitterclone.contracts.StreamCheckpointItem;
import dev.twitterclone.contracts.TableSchemas;
import dev.twitterclone.contracts.TweetItem;
import dev.twitterclone.platform.aws.DynamoDbConfig;
import dev.twitterclone.platform.aws.DynamoDbProperties;
import dev.twitterclone.platform.aws.streams.DynamoDbStreamCheckpoints;
import dev.twitterclone.platform.aws.streams.DynamoDbStreamsConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

/**
 * The three tables this service reads or writes.
 *
 * <p>{@code tweets} is the only one it is the sole writer of, and the only one carrying a stream.
 * {@code likes} it owns outright; {@code idempotency} is shared with every other service that
 * accepts a write, which is why the schema for it lives in {@code services:contracts}. {@code
 * stream_checkpoints} is shared with the fan-out worker but partitioned by consumer group, so the
 * search indexer's progress and fan-out's cannot collide.
 */
@Configuration(proxyBeanMethods = false)
@Import({DynamoDbConfig.class, DynamoDbStreamsConfig.class})
public class DynamoDbTablesConfig {

  @Bean
  public DynamoDbTable<TweetItem> tweetsTable(
      DynamoDbEnhancedClient enhanced, DynamoDbProperties properties) {
    return enhanced.table(properties.table("tweets"), TableSchemas.TWEET);
  }

  @Bean
  public DynamoDbTable<LikeItem> likesTable(
      DynamoDbEnhancedClient enhanced, DynamoDbProperties properties) {
    return enhanced.table(properties.table("likes"), TableSchemas.LIKE);
  }

  @Bean
  public DynamoDbTable<StreamCheckpointItem> streamCheckpointsTable(
      DynamoDbEnhancedClient enhanced, DynamoDbProperties properties) {
    return enhanced.table(properties.table("stream_checkpoints"), TableSchemas.STREAM_CHECKPOINT);
  }

  @Bean
  public DynamoDbStreamCheckpoints streamCheckpoints(
      DynamoDbTable<StreamCheckpointItem> streamCheckpointsTable) {
    return new DynamoDbStreamCheckpoints(streamCheckpointsTable);
  }

  @Bean
  public DynamoDbTable<IdempotencyItem> idempotencyTable(
      DynamoDbEnhancedClient enhanced, DynamoDbProperties properties) {
    return enhanced.table(properties.table("idempotency"), TableSchemas.IDEMPOTENCY);
  }
}
