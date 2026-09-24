/* SPDX-License-Identifier: MIT */
package dev.twitterclone.fanout.config;

import dev.twitterclone.contracts.StreamCheckpointItem;
import dev.twitterclone.contracts.TableSchemas;
import dev.twitterclone.contracts.TimelineEntryItem;
import dev.twitterclone.contracts.UserItem;
import dev.twitterclone.fanout.domain.StreamConsumer;
import dev.twitterclone.platform.aws.DynamoDbConfig;
import dev.twitterclone.platform.aws.DynamoDbProperties;
import dev.twitterclone.platform.aws.streams.DynamoDbStreamsConfig;
import dev.twitterclone.platform.aws.streams.StreamHealthIndicator;
import dev.twitterclone.platform.aws.streams.StreamMetrics;
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
@Import({DynamoDbConfig.class, DynamoDbStreamsConfig.class})
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

  /**
   * Readiness for the stream this worker exists to consume.
   *
   * <p>Reports out of service only while fan-out is switched on. A worker started with fan-out off
   * is not waiting for anything, and marking it unready would be a lie that never resolves.
   *
   * @param consumer the consumer whose stream is reported
   * @param properties supplies whether fan-out is switched on
   * @return the indicator, named {@code stream} in the health response
   */
  @Bean("stream")
  public StreamHealthIndicator streamHealthIndicator(
      StreamConsumer consumer, FanoutProperties properties) {
    return new StreamHealthIndicator(consumer.streamSource(), properties.enabled());
  }

  /**
   * The same stream state as a metric, for the history the probe cannot keep.
   *
   * @param consumer the consumer whose stream is reported
   * @return the binder
   */
  @Bean
  public StreamMetrics streamMetrics(StreamConsumer consumer) {
    return new StreamMetrics(consumer.streamSource(), StreamCheckpointItem.GROUP_FANOUT);
  }
}
