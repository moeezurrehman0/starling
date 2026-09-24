/* SPDX-License-Identifier: MIT */
package dev.starling.platform.aws.streams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;

/** The gauges that give gap register row 18 a history rather than only a probe. */
@ExtendWith(MockitoExtension.class)
class StreamMetricsTest {

  private static final String ARN = "arn:aws:dynamodb:eu-central-1:000:table/tweets/stream/2024";

  private final MeterRegistry registry = new SimpleMeterRegistry();

  @Mock private DynamoDbClient dynamo;

  private StreamSource source() {
    return new StreamArns(dynamo).source("", "tweets");
  }

  private static DescribeTableResponse describes(String arn) {
    return DescribeTableResponse.builder()
        .table(TableDescription.builder().latestStreamArn(arn).build())
        .build();
  }

  @Test
  void reportsZeroWhileTheStreamIsUndiscoverable() {
    when(dynamo.describeTable(any(DescribeTableRequest.class)))
        .thenThrow(ApiCallTimeoutException.builder().build());
    StreamSource unresolvable = source();
    new StreamMetrics(unresolvable, "fanout").bindTo(registry);
    unresolvable.get();

    assertThat(registry.get("stream.resolved").tag("table", "tweets").gauge().value()).isZero();
    assertThat(registry.get("stream.resolve.attempts").gauge().value()).isEqualTo(1);
  }

  @Test
  void reportsOneOnceItIsFound() {
    when(dynamo.describeTable(any(DescribeTableRequest.class))).thenReturn(describes(ARN));
    StreamSource resolvable = source();
    new StreamMetrics(resolvable, "fanout").bindTo(registry);
    resolvable.get();

    assertThat(registry.get("stream.resolved").gauge().value()).isEqualTo(1);
  }

  @Test
  void attemptsStopClimbingOnceResolved() {
    // The rate of this gauge is the signal, not its value: flat means resolved, climbing
    // means a consumer is retrying and no probe has noticed yet.
    when(dynamo.describeTable(any(DescribeTableRequest.class)))
        .thenThrow(ApiCallTimeoutException.builder().build())
        .thenThrow(ApiCallTimeoutException.builder().build())
        .thenReturn(describes(ARN));
    StreamSource flaky = source();
    new StreamMetrics(flaky, "fanout").bindTo(registry);

    flaky.get();
    flaky.get();
    flaky.get();
    double afterSuccess = registry.get("stream.resolve.attempts").gauge().value();
    flaky.get();
    flaky.get();

    assertThat(afterSuccess).isEqualTo(3);
    assertThat(registry.get("stream.resolve.attempts").gauge().value()).isEqualTo(afterSuccess);
  }

  @Test
  void twoConsumersOfOneTableStayDistinguishable() {
    // fanout and the search indexer read the same stream. Without the group tag the second
    // binder silently replaces the first and one of the two consumers stops being observable.
    when(dynamo.describeTable(any(DescribeTableRequest.class)))
        .thenReturn(describes(ARN))
        .thenThrow(ApiCallTimeoutException.builder().build());
    StreamSource a = source();
    StreamSource b = source();
    new StreamMetrics(a, "fanout").bindTo(registry);
    new StreamMetrics(b, "search-indexer").bindTo(registry);
    a.get();
    b.get();

    assertThat(registry.get("stream.resolved").tag("group", "fanout").gauge().value()).isEqualTo(1);
    assertThat(registry.get("stream.resolved").tag("group", "search-indexer").gauge().value())
        .isZero();
  }
}
