/* SPDX-License-Identifier: MIT */
package dev.starling.tweet.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.starling.contracts.StreamCheckpointItem;
import dev.starling.platform.aws.DynamoDbProperties;
import dev.starling.platform.aws.streams.DynamoDbStreamCheckpoints;
import dev.starling.platform.aws.streams.StreamArns;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DescribeStreamRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeStreamResponse;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GetRecordsRequest;
import software.amazon.awssdk.services.dynamodb.model.GetRecordsResponse;
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorRequest;
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorResponse;
import software.amazon.awssdk.services.dynamodb.model.OperationType;
import software.amazon.awssdk.services.dynamodb.model.Record;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.Shard;
import software.amazon.awssdk.services.dynamodb.model.StreamDescription;
import software.amazon.awssdk.services.dynamodb.model.StreamRecord;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TweetIndexer")
class TweetIndexerTest {

  private static final String SHARD = "shard-1";
  private static final SearchProperties PROPERTIES =
      new SearchProperties("arn:stream", Duration.ofSeconds(1), Duration.ofSeconds(1), 100, true);

  @Mock private DynamoDbStreamsClient streams;
  @Mock private DynamoDbStreamCheckpoints checkpoints;
  @Mock private SearchIndex index;
  @Mock private DynamoDbClient dynamo;

  private static final DynamoDbProperties TABLES = new DynamoDbProperties(null, "");

  private TweetIndexer indexer() {
    return new TweetIndexer(
        streams, checkpoints, index, PROPERTIES, new StreamArns(dynamo), TABLES);
  }

  private void oneShard() {
    when(streams.describeStream(any(DescribeStreamRequest.class)))
        .thenReturn(
            DescribeStreamResponse.builder()
                .streamDescription(
                    StreamDescription.builder()
                        .shards(Shard.builder().shardId(SHARD).build())
                        .build())
                .build());
    when(streams.getShardIterator(any(GetShardIteratorRequest.class)))
        .thenReturn(GetShardIteratorResponse.builder().shardIterator("iter").build());
    when(checkpoints.lastSequenceNumber(anyString(), anyString())).thenReturn(Optional.empty());
  }

  private void streamReturns(Record... records) {
    when(streams.getRecords(any(GetRecordsRequest.class)))
        .thenReturn(GetRecordsResponse.builder().records(List.of(records)).build());
  }

  private static Record insert(Map<String, String> image) {
    return Record.builder()
        .eventName(OperationType.INSERT)
        .dynamodb(
            StreamRecord.builder()
                .sequenceNumber("1")
                .newImage(
                    image.entrySet().stream()
                        .collect(
                            java.util.stream.Collectors.toMap(
                                Map.Entry::getKey, e -> AttributeValue.fromS(e.getValue()))))
                .build())
        .build();
  }

  private static Map<String, String> tweet(String text) {
    return Map.of("tid", "t1", "aid", "a1", "txt", text, "ca", "2025-01-01T00:00:00Z");
  }

  @Nested
  @DisplayName("indexing")
  class Indexing {

    @Test
    @DisplayName("indexes a new tweet")
    void indexesInserts() {
      oneShard();
      streamReturns(insert(tweet("hello world")));

      indexer().pollOnce();

      verify(index).index("t1", "a1", "hello world", Instant.parse("2025-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("ignores a modify, because a like rewrites the tweet row")
    void ignoresModifies() {
      oneShard();
      Record modify =
          Record.builder()
              .eventName(OperationType.MODIFY)
              .dynamodb(
                  StreamRecord.builder()
                      .sequenceNumber("1")
                      .newImage(Map.of("tid", AttributeValue.fromS("t1")))
                      .build())
              .build();
      streamReturns(modify);

      indexer().pollOnce();

      verify(index, never()).index(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("skips a bare retweet, which has no words of its own")
    void skipsEmptyText() {
      oneShard();
      streamReturns(insert(Map.of("tid", "t1", "aid", "a1", "txt", "   ")));

      indexer().pollOnce();

      verify(index, never()).index(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("skips a record with no text attribute at all")
    void skipsMissingText() {
      oneShard();
      streamReturns(insert(Map.of("tid", "t1", "aid", "a1")));

      indexer().pollOnce();

      verify(index, never()).index(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("skips a record missing its ids rather than indexing a broken row")
    void skipsMissingIds() {
      oneShard();
      streamReturns(insert(Map.of("txt", "orphan")));

      indexer().pollOnce();

      verify(index, never()).index(anyString(), anyString(), anyString(), any());
    }
  }

  @Nested
  @DisplayName("timestamps")
  class Timestamps {

    @Test
    @DisplayName("indexes at the current time when the timestamp is unparseable")
    void unparseableTimestamp() {
      oneShard();
      streamReturns(insert(Map.of("tid", "t1", "aid", "a1", "txt", "hi", "ca", "not-a-timestamp")));
      Instant before = Instant.now();

      indexer().pollOnce();

      ArgumentCaptor<Instant> at = ArgumentCaptor.forClass(Instant.class);
      verify(index).index(eq("t1"), eq("a1"), eq("hi"), at.capture());
      assertThat(at.getValue()).isAfterOrEqualTo(before);
    }

    @Test
    @DisplayName("indexes at the current time when the timestamp is absent")
    void missingTimestamp() {
      oneShard();
      streamReturns(insert(Map.of("tid", "t1", "aid", "a1", "txt", "hi")));
      Instant before = Instant.now();

      indexer().pollOnce();

      ArgumentCaptor<Instant> at = ArgumentCaptor.forClass(Instant.class);
      verify(index).index(eq("t1"), eq("a1"), eq("hi"), at.capture());
      assertThat(at.getValue()).isAfterOrEqualTo(before);
    }
  }

  @Nested
  @DisplayName("consumer group")
  class ConsumerGroup {

    @Test
    @DisplayName("checkpoints under the search group, not fan-out's")
    void usesItsOwnGroup() {
      oneShard();
      streamReturns(insert(tweet("hello")));

      indexer().pollOnce();

      verify(checkpoints).save(StreamCheckpointItem.GROUP_SEARCH, SHARD, "1");
      verify(checkpoints, never())
          .save(eq(StreamCheckpointItem.GROUP_FANOUT), anyString(), anyString());
    }

    @Test
    @DisplayName("does nothing when no stream is configured")
    void disabledWithoutArn() {
      // Blank configuration falls back to discovery, and discovery finding nothing must be
      // the same "idle" as no configuration at all rather than a startup failure.
      when(dynamo.describeTable(any(DescribeTableRequest.class)))
          .thenThrow(ResourceNotFoundException.builder().message("no such table").build());

      TweetIndexer idle =
          new TweetIndexer(
              streams,
              checkpoints,
              index,
              new SearchProperties("", Duration.ofSeconds(1), Duration.ofSeconds(1), 100, true),
              new StreamArns(dynamo),
              TABLES);

      assertThat(idle.pollOnce()).isZero();
      verify(streams, never()).describeStream(any(DescribeStreamRequest.class));
    }
  }
}
