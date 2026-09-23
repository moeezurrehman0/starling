/* SPDX-License-Identifier: MIT */
package dev.twitterclone.timeline.config;

import dev.twitterclone.contracts.TableSchemas;
import dev.twitterclone.contracts.TimelineEntryItem;
import dev.twitterclone.platform.aws.DynamoDbConfig;
import dev.twitterclone.platform.aws.DynamoDbProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

/**
 * The one table this service touches, and it only reads it.
 *
 * <p>{@code timelines} is written exclusively by fanout-worker. Keeping the read and the write on
 * opposite sides of a service boundary is what allows fan-out to be slow and bursty without ever
 * making a timeline render slow.
 */
@Configuration(proxyBeanMethods = false)
@Import(DynamoDbConfig.class)
public class DynamoDbTablesConfig {

  @Bean
  public DynamoDbTable<TimelineEntryItem> timelinesTable(
      DynamoDbEnhancedClient enhanced, DynamoDbProperties properties) {
    return enhanced.table(properties.table("timelines"), TableSchemas.TIMELINE_ENTRY);
  }
}
