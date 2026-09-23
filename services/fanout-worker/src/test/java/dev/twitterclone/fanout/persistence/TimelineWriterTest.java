/* SPDX-License-Identifier: MIT */
package dev.twitterclone.fanout.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.twitterclone.contracts.TableSchemas;
import dev.twitterclone.contracts.TimelineEntryItem;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchWriteItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchWriteResult;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TimelineWriter")
class TimelineWriterTest {

  @Mock private DynamoDbEnhancedClient enhanced;
  @Mock private DynamoDbTable<TimelineEntryItem> timelines;
  @Mock private BatchWriteResult result;

  private TimelineWriter writer() {
    // WriteBatch serialises each item eagerly through the table's schema, so the mock has to
    // hand back the real one -- a null schema fails inside the SDK, not in the assertion.
    when(timelines.tableSchema()).thenReturn(TableSchemas.TIMELINE_ENTRY);
    return new TimelineWriter(enhanced, timelines);
  }

  private static List<TimelineEntryItem> entries(int count) {
    return IntStream.range(0, count)
        .mapToObj(
            i ->
                TimelineEntryItem.builder()
                    .ownerId("owner-" + i)
                    .tweetId("tweet")
                    .authorId("author")
                    .createdAt(Instant.EPOCH)
                    .expiresAt(Instant.EPOCH.plus(TimelineEntryItem.RETENTION).getEpochSecond())
                    .build())
        .toList();
  }

  private void accepting() {
    when(result.unprocessedPutItemsForTable(timelines)).thenReturn(List.of());
    when(enhanced.batchWriteItem(any(BatchWriteItemEnhancedRequest.class))).thenReturn(result);
  }

  @Test
  @DisplayName("sends a single request when the batch fits")
  void singleBatch() {
    accepting();

    assertThat(writer().write(entries(10))).isEqualTo(10);
    verify(enhanced, times(1)).batchWriteItem(any(BatchWriteItemEnhancedRequest.class));
  }

  @Test
  @DisplayName("splits at DynamoDB's 25-item ceiling")
  void chunks() {
    accepting();

    // BatchWriteItem rejects the whole request above 25 items, so a popular author's fan-out
    // would fail entirely rather than partially -- and only once they became popular.
    assertThat(writer().write(entries(60))).isEqualTo(60);
    verify(enhanced, times(3)).batchWriteItem(any(BatchWriteItemEnhancedRequest.class));
  }

  @Test
  @DisplayName("re-sends the items DynamoDB declined")
  void retriesUnprocessed() {
    List<TimelineEntryItem> all = entries(3);
    when(result.unprocessedPutItemsForTable(timelines))
        .thenReturn(List.of(all.get(2)))
        .thenReturn(List.of());
    when(enhanced.batchWriteItem(any(BatchWriteItemEnhancedRequest.class))).thenReturn(result);

    assertThat(writer().write(all)).isEqualTo(3);
    verify(enhanced, times(2)).batchWriteItem(any(BatchWriteItemEnhancedRequest.class));
  }

  @Test
  @DisplayName("gives up after five attempts and reports what it actually wrote")
  void givesUp() {
    List<TimelineEntryItem> all = entries(3);
    when(result.unprocessedPutItemsForTable(timelines)).thenReturn(List.of(all.get(2)));
    when(enhanced.batchWriteItem(any(BatchWriteItemEnhancedRequest.class))).thenReturn(result);

    // Sustained throttling is a capacity problem, not a transient one; retrying forever would
    // convert it into a stalled shard. The honest return value is what matters.
    assertThat(writer().write(all)).isEqualTo(2);
    verify(enhanced, times(5)).batchWriteItem(any(BatchWriteItemEnhancedRequest.class));
  }

  @Test
  @DisplayName("writes nothing for an empty collection")
  void empty() {
    assertThat(writer().write(List.of())).isZero();
    verify(enhanced, times(0)).batchWriteItem(any(BatchWriteItemEnhancedRequest.class));
  }
}
