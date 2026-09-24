/* SPDX-License-Identifier: MIT */
package dev.starling.platform.aws.streams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;

@ExtendWith(MockitoExtension.class)
@DisplayName("StreamHealthIndicator")
class StreamHealthIndicatorTest {

  private static final String ARN = "arn:aws:dynamodb:eu-central-1:000:table/tweets/stream/2024";

  @Mock private DynamoDbClient dynamo;

  private StreamSource source() {
    return new StreamArns(dynamo).source("", "tweets");
  }

  @Test
  @DisplayName("is out of service while a consuming process has not found its stream")
  void unresolvedWhileConsuming() {
    when(dynamo.describeTable(any(DescribeTableRequest.class)))
        .thenThrow(ApiCallTimeoutException.builder().message("timed out").build());

    StreamSource source = source();
    source.get();

    Health health = new StreamHealthIndicator(source, true).health();

    assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    assertThat(health.getDetails())
        .containsEntry("table", "tweets")
        .containsEntry("consuming", true)
        .containsEntry("resolved", false);
  }

  @Test
  @DisplayName("is up once the stream is found")
  void resolved() {
    when(dynamo.describeTable(any(DescribeTableRequest.class)))
        .thenReturn(
            DescribeTableResponse.builder()
                .table(TableDescription.builder().latestStreamArn(ARN).build())
                .build());

    StreamSource source = source();
    source.get();

    assertThat(new StreamHealthIndicator(source, true).health().getStatus()).isEqualTo(Status.UP);
  }

  @Test
  @DisplayName("is up when this process is not supposed to consume, however unresolved it is")
  void notConsuming() {
    // The request-serving tweet-service replicas. An indexing problem must not be able to
    // take every one of them out of the Service and stop writes entirely.
    Health health = new StreamHealthIndicator(source(), false).health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
        .containsEntry("consuming", false)
        .containsEntry("resolved", false);
  }

  @Test
  @DisplayName("performs no I/O, so a slow dependency cannot decide the probe result")
  void doesNotResolve() {
    StreamSource source = source();

    new StreamHealthIndicator(source, true).health();

    assertThat(source.attempts()).isZero();
  }
}
