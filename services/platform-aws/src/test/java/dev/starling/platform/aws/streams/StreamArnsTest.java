/* SPDX-License-Identifier: MIT */
package dev.starling.platform.aws.streams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;

@ExtendWith(MockitoExtension.class)
@DisplayName("StreamArns")
class StreamArnsTest {

  private static final String ARN = "arn:aws:dynamodb:eu-central-1:000:table/tweets/stream/2024";

  @Mock private DynamoDbClient dynamo;

  private void describeReturns(String latestStreamArn) {
    when(dynamo.describeTable(any(DescribeTableRequest.class)))
        .thenReturn(
            DescribeTableResponse.builder()
                .table(TableDescription.builder().latestStreamArn(latestStreamArn).build())
                .build());
  }

  @Test
  @DisplayName("prefers the configured ARN without calling AWS")
  void configuredWins() {
    Optional<String> resolved = new StreamArns(dynamo).resolve(ARN, "tweets");

    assertThat(resolved).contains(ARN);
    verify(dynamo, never()).describeTable(any(DescribeTableRequest.class));
  }

  @Test
  @DisplayName("discovers the ARN from the table when the configuration is blank")
  void discovers() {
    describeReturns(ARN);

    assertThat(new StreamArns(dynamo).resolve("  ", "tweets")).contains(ARN);
  }

  @Test
  @DisplayName("discovers the ARN when the configuration is null")
  void discoversForNull() {
    describeReturns(ARN);

    assertThat(new StreamArns(dynamo).resolve(null, "tweets")).contains(ARN);
  }

  @Test
  @DisplayName("is empty when the table exists but has no stream")
  void noStreamOnTable() {
    describeReturns(null);

    assertThat(new StreamArns(dynamo).resolve("", "tweets")).isEmpty();
  }

  @Test
  @DisplayName("is empty when the table reports a blank stream ARN")
  void blankStreamOnTable() {
    describeReturns("");

    assertThat(new StreamArns(dynamo).resolve("", "tweets")).isEmpty();
  }

  @Test
  @DisplayName("is empty rather than failing when the table cannot be described")
  void describeFails() {
    when(dynamo.describeTable(any(DescribeTableRequest.class)))
        .thenThrow(ResourceNotFoundException.builder().message("no such table").build());

    assertThat(new StreamArns(dynamo).resolve("", "tweets")).isEmpty();
  }
}
