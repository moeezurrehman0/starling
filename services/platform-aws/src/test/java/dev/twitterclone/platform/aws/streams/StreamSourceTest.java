/* SPDX-License-Identifier: MIT */
package dev.twitterclone.platform.aws.streams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;

/**
 * The regression suite for a failure that reached a running cluster.
 *
 * <p>Discovery used to happen once, in a consumer's constructor. One {@code
 * ApiCallTimeoutException} at start-up left the consumer holding a blank ARN forever: running,
 * ready, and silently consuming nothing. {@link #retriesUntilItSucceeds} is the test that would
 * have caught it.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StreamSource")
class StreamSourceTest {

  private static final String ARN = "arn:aws:dynamodb:eu-central-1:000:table/tweets/stream/2024";

  @Mock private DynamoDbClient dynamo;

  private StreamSource source() {
    return new StreamArns(dynamo).source("", "tweets");
  }

  private DescribeTableResponse describes(String arn) {
    return DescribeTableResponse.builder()
        .table(TableDescription.builder().latestStreamArn(arn).build())
        .build();
  }

  @Test
  @DisplayName("resolves on first use rather than at construction")
  void lazy() {
    StreamSource source = source();

    verify(dynamo, never()).describeTable(any(DescribeTableRequest.class));
    assertThat(source.resolved()).isFalse();
    assertThat(source.attempts()).isZero();
  }

  @Test
  @DisplayName("retries until it succeeds, instead of giving up on the first failure")
  void retriesUntilItSucceeds() {
    when(dynamo.describeTable(any(DescribeTableRequest.class)))
        .thenThrow(ApiCallTimeoutException.builder().message("did not complete in 5000 ms").build())
        .thenThrow(ApiCallTimeoutException.builder().message("did not complete in 5000 ms").build())
        .thenReturn(describes(ARN));

    StreamSource source = source();

    assertThat(source.get()).isEmpty();
    assertThat(source.resolved()).isFalse();
    assertThat(source.get()).isEmpty();
    assertThat(source.get()).isEqualTo(ARN);
    assertThat(source.resolved()).isTrue();
  }

  @Test
  @DisplayName("caches success, so a resolved stream costs no further DescribeTable calls")
  void cachesSuccess() {
    when(dynamo.describeTable(any(DescribeTableRequest.class))).thenReturn(describes(ARN));

    StreamSource source = source();
    for (int i = 0; i < 5; i++) {
      assertThat(source.get()).isEqualTo(ARN);
    }

    verify(dynamo, times(1)).describeTable(any(DescribeTableRequest.class));
    assertThat(source.attempts()).isEqualTo(1);
  }

  @Test
  @DisplayName("does not call AWS at all when the ARN is configured")
  void configuredNeedsNoDiscovery() {
    StreamSource source = new StreamArns(dynamo).source(ARN, "tweets");

    assertThat(source.get()).isEqualTo(ARN);
    assertThat(source.resolved()).isTrue();
    verify(dynamo, never()).describeTable(any(DescribeTableRequest.class));
  }

  @Test
  @DisplayName("keeps trying when the table has no stream yet, because it may gain one")
  void tableWithoutStream() {
    when(dynamo.describeTable(any(DescribeTableRequest.class)))
        .thenReturn(describes(null))
        .thenReturn(describes(ARN));

    StreamSource source = source();

    assertThat(source.get()).isEmpty();
    assertThat(source.get()).isEqualTo(ARN);
  }

  @Test
  @DisplayName("counts attempts so an operator can tell a slow start from a stuck one")
  void countsAttempts() {
    when(dynamo.describeTable(any(DescribeTableRequest.class))).thenReturn(describes(null));

    StreamSource source = source();
    for (int i = 0; i < 3; i++) {
      source.get();
    }

    assertThat(source.attempts()).isEqualTo(3);
    assertThat(source.resolved()).isFalse();
  }

  @Test
  @DisplayName("reports the table it is looking for, which is what an operator needs")
  void reportsTable() {
    assertThat(source().tableName()).isEqualTo("tweets");
  }
}
