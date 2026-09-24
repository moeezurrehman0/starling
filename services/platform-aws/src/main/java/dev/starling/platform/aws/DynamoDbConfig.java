/* SPDX-License-Identifier: MIT */
package dev.starling.platform.aws;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * The DynamoDB client every service uses.
 *
 * <p>Spring Boot has no DynamoDB auto-configuration, so this would otherwise be written out once
 * per service. It was, briefly, and the second copy is what prompted this module: five
 * near-identical clients means five places to change a timeout and four places to forget. The table
 * beans are deliberately <em>not</em> here -- those differ per service and belong with the service
 * that owns them.
 *
 * <p>Imported explicitly rather than registered as an auto-configuration. A service should be able
 * to say, in one place, that it talks to DynamoDB; a bean appearing because a jar is on the
 * classpath is how a batch job ends up with an HTTP connection pool it never asked for.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DynamoDbProperties.class)
public class DynamoDbConfig {

  @Bean
  public DynamoDbClient dynamoDbClient(DynamoDbProperties properties) {
    var builder =
        DynamoDbClient.builder()
            // The synchronous Apache client, not Netty. Every handler runs on a virtual
            // thread, so a blocking call costs a carrier thread nothing, and the synchronous
            // path keeps stack traces readable and avoids dragging a second concurrency model
            // through the codebase for no throughput gain.
            .httpClientBuilder(
                Apache5HttpClient.builder()
                    .maxConnections(100)
                    .connectionTimeout(Duration.ofSeconds(2)))
            .overrideConfiguration(
                ClientOverrideConfiguration.builder()
                    // Standard, not adaptive. Adaptive adds a client-side rate limiter that
                    // holds requests back after a throttle -- good for a batch job hammering
                    // one hot partition, wrong here, because this client is shared across
                    // every table a service touches and a throttle on one would start
                    // delaying calls to the others. Standard still backs off with jitter and
                    // still spends from a retry token bucket, so a retry storm cannot form;
                    // it just refuses to penalise unrelated traffic.
                    .retryStrategy(AwsRetryStrategy.standardRetryStrategy())
                    // A single call budget, so a slow dependency surfaces as a fast failure
                    // the circuit breaker can see instead of a request that hangs until the
                    // client times out.
                    .apiCallTimeout(Duration.ofSeconds(5))
                    .apiCallAttemptTimeout(Duration.ofSeconds(2))
                    .build());

    // Present only in Tier L. Everywhere else the SDK resolves the regional endpoint and the
    // credentials from the pod's IRSA role, which is what keeps static keys out of the cluster.
    if (properties.endpoint() != null) {
      builder.endpointOverride(properties.endpoint());
    }

    return builder.build();
  }

  @Bean
  public DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient client) {
    return DynamoDbEnhancedClient.builder().dynamoDbClient(client).build();
  }
}
