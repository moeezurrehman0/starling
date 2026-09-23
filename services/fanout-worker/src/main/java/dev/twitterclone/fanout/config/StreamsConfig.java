/* SPDX-License-Identifier: MIT */
package dev.twitterclone.fanout.config;

import dev.twitterclone.platform.aws.DynamoDbProperties;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient;

/**
 * The Streams client, which is not the data-plane client.
 *
 * <p>Streams is a separate service endpoint rather than extra operations on {@code DynamoDbClient},
 * so it needs its own client even though it reads the same table.
 *
 * <p>The timeouts here are deliberately longer than the shared ones in {@code platform-aws}. {@code
 * GetRecords} against an idle shard is not slow because anything is wrong — it is a poll that found
 * nothing — and a two-second attempt timeout would abort healthy calls and make the consumer look
 * like it was failing when it was merely waiting.
 */
@Configuration(proxyBeanMethods = false)
public class StreamsConfig {

  @Bean
  public DynamoDbStreamsClient dynamoDbStreamsClient(DynamoDbProperties properties) {
    var builder =
        DynamoDbStreamsClient.builder()
            .httpClientBuilder(
                ApacheHttpClient.builder()
                    .maxConnections(20)
                    .connectionTimeout(Duration.ofSeconds(2)))
            .overrideConfiguration(
                ClientOverrideConfiguration.builder()
                    .retryStrategy(AwsRetryStrategy.standardRetryStrategy())
                    .apiCallTimeout(Duration.ofSeconds(30))
                    .apiCallAttemptTimeout(Duration.ofSeconds(20))
                    .build());
    if (properties.endpoint() != null) {
      builder.endpointOverride(properties.endpoint());
    }
    return builder.build();
  }
}
