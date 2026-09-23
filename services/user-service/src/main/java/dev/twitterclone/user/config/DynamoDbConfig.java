/* SPDX-License-Identifier: MIT */
package dev.twitterclone.user.config;

import dev.twitterclone.contracts.FollowItem;
import dev.twitterclone.contracts.HandleItem;
import dev.twitterclone.contracts.TableSchemas;
import dev.twitterclone.contracts.UserItem;
import dev.twitterclone.user.persistence.CredentialItem;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * The DynamoDB client and the three tables this service owns.
 *
 * <p>Spring Boot has no DynamoDB auto-configuration, which is fortunate here: the client is built
 * explicitly so that the choices below are visible rather than inherited.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DynamoDbProperties.class)
public class DynamoDbConfig {

  @Bean
  public DynamoDbClient dynamoDbClient(DynamoDbProperties properties) {
    var builder =
        DynamoDbClient.builder()
            // The synchronous Apache client, not Netty. Every handler here runs on a virtual
            // thread, so a blocking call costs a carrier thread nothing, and the synchronous
            // path keeps stack traces readable and avoids dragging a second concurrency model
            // through the codebase for no throughput gain.
            .httpClientBuilder(
                ApacheHttpClient.builder()
                    .maxConnections(100)
                    .connectionTimeout(Duration.ofSeconds(2)))
            .overrideConfiguration(
                ClientOverrideConfiguration.builder()
                    // Standard, not adaptive. Adaptive adds a client-side rate limiter that
                    // holds requests back after a throttle -- good for a batch job hammering
                    // one hot partition, wrong here, because this client is shared across
                    // three tables and a throttle on one would start delaying calls to the
                    // other two. Standard still backs off with jitter and still spends from a
                    // retry token bucket, so a retry storm cannot form; it just refuses to
                    // penalise unrelated traffic.
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
