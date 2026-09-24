/* SPDX-License-Identifier: MIT */
package dev.starling.platform.aws.streams;

import dev.starling.platform.aws.DynamoDbProperties;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient;

/**
 * The Streams client, which is not the data-plane client.
 *
 * <p>Streams is a separate service endpoint rather than extra operations on {@code DynamoDbClient},
 * so it needs its own client even though it reads the same table.
 *
 * <p>The timeouts here are deliberately longer than the data-plane client's. {@code GetRecords}
 * against an idle shard is not slow because anything is wrong — it is a poll that found nothing —
 * and a two-second attempt timeout would abort healthy calls and make the consumer look like it was
 * failing when it was merely waiting.
 *
 * <p>Imported explicitly by the services that consume a stream rather than auto-configured for
 * every service. Two of the five need it; the other three would pay a second HTTP client and a
 * second connection pool for a client they never call.
 */
@Configuration(proxyBeanMethods = false)
public class DynamoDbStreamsConfig {

  @Bean
  public DynamoDbStreamsClient dynamoDbStreamsClient(DynamoDbProperties properties) {
    var builder =
        DynamoDbStreamsClient.builder()
            .httpClientBuilder(
                Apache5HttpClient.builder()
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

  /**
   * Resolves a blank stream ARN against the table that owns the stream.
   *
   * <p>Lives here rather than in each consumer because both consumers need it and both would
   * otherwise re-implement the same {@code DescribeTable} call with different failure behaviour.
   *
   * @param dynamo the data-plane client, which also serves {@code DescribeTable}
   * @return the resolver
   */
  @Bean
  public StreamArns streamArns(DynamoDbClient dynamo) {
    return new StreamArns(dynamo);
  }
}
