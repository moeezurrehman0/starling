/* SPDX-License-Identifier: MIT */
package dev.twitterclone.fanout.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.twitterclone.contracts.StreamCheckpointItem;
import dev.twitterclone.fanout.config.FanoutProperties;
import dev.twitterclone.fanout.persistence.CheckpointRepository;
import dev.twitterclone.platform.aws.DynamoDbProperties;
import dev.twitterclone.platform.aws.streams.StreamArns;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
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
import software.amazon.awssdk.services.dynamodb.model.ExpiredIteratorException;
import software.amazon.awssdk.services.dynamodb.model.GetRecordsRequest;
import software.amazon.awssdk.services.dynamodb.model.GetRecordsResponse;
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorRequest;
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorResponse;
import software.amazon.awssdk.services.dynamodb.model.OperationType;
import software.amazon.awssdk.services.dynamodb.model.Record;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.Shard;
import software.amazon.awssdk.services.dynamodb.model.ShardIteratorType;
import software.amazon.awssdk.services.dynamodb.model.StreamDescription;
import software.amazon.awssdk.services.dynamodb.model.StreamRecord;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("StreamConsumer")
class StreamConsumerTest {

  private static final String SHARD = "shard-1";

  private static final FanoutProperties PROPERTIES =
      new FanoutProperties(
          "arn:stream", Duration.ofSeconds(1), Duration.ofSeconds(5), 100, 50_000, true);

  @Mock private DynamoDbStreamsClient streams;
  @Mock private CheckpointRepository checkpoints;
  @Mock private FanoutService fanout;
  @Mock private DynamoDbClient dynamo;

  private static final DynamoDbProperties TABLES = new DynamoDbProperties(null, "");

  // A real registry rather than a mock. The meters are cheap, and a mock would accept every
  // interaction including the ones that would throw against Micrometer's actual contract.
  private final MeterRegistry meters = new SimpleMeterRegistry();

  private StreamConsumer consumer(FanoutProperties properties) {
    return new StreamConsumer(
        streams,
        checkpoints,
        fanout,
        properties,
        new StreamArns(dynamo),
        TABLES,
        new FanoutMetrics(meters));
  }

  private StreamConsumer consumer() {
    return consumer(PROPERTIES);
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
  }

  private static Record record(OperationType type, String sequence, Map<String, String> image) {
    StreamRecord.Builder stream = StreamRecord.builder().sequenceNumber(sequence);
    if (image != null) {
      stream.newImage(
          image.entrySet().stream()
              .collect(
                  java.util.stream.Collectors.toMap(
                      Map.Entry::getKey, e -> AttributeValue.fromS(e.getValue()))));
    }
    return Record.builder().eventName(type).dynamodb(stream.build()).build();
  }

  private void records(Record... records) {
    when(streams.getRecords(any(GetRecordsRequest.class)))
        .thenReturn(GetRecordsResponse.builder().records(List.of(records)).build());
  }

  @Nested
  @DisplayName("which records matter")
  class Filtering {

    @Test
    @DisplayName("fans out an inserted tweet")
    void insert() {
      oneShard();
      records(record(OperationType.INSERT, "1", Map.of("tid", "t1", "aid", "bob")));
      when(fanout.fanOut(anyString(), anyString()))
          .thenReturn(new FanoutService.Result("t1", FanoutService.Outcome.FANNED_OUT, 1));

      assertThat(consumer().pollOnce()).isEqualTo(1);
      verify(fanout).fanOut("t1", "bob");
    }

    @Test
    @DisplayName("ignores MODIFY, which is what a like looks like")
    void modifyIsIgnored() {
      oneShard();
      records(record(OperationType.MODIFY, "1", Map.of("tid", "t1", "aid", "bob")));

      // The like counter lives on the tweet row, so every heart tapped produces a MODIFY.
      // Fanning those out would rewrite every follower's timeline each time -- turning a
      // single-item update into millions of writes.
      consumer().pollOnce();
      verify(fanout, never()).fanOut(anyString(), anyString());
    }

    @Test
    @DisplayName("ignores REMOVE")
    void removeIsIgnored() {
      oneShard();
      records(record(OperationType.REMOVE, "1", null));

      // Timeline rows are left behind on delete deliberately: unwinding them would make
      // every delete an O(followers) write. The read path drops ids with no body instead.
      consumer().pollOnce();
      verify(fanout, never()).fanOut(anyString(), anyString());
    }

    @Test
    @DisplayName("skips a record with no ids rather than failing the shard")
    void malformedRecord() {
      oneShard();
      records(record(OperationType.INSERT, "1", Map.of("tid", "t1")));

      consumer().pollOnce();
      verify(fanout, never()).fanOut(anyString(), anyString());
    }
  }

  @Nested
  @DisplayName("checkpointing")
  class Checkpointing {

    @Test
    @DisplayName("advances to the last record of the batch, after processing it")
    void savesAfterTheBatch() {
      oneShard();
      records(
          record(OperationType.INSERT, "1", Map.of("tid", "t1", "aid", "bob")),
          record(OperationType.INSERT, "2", Map.of("tid", "t2", "aid", "bob")));
      when(fanout.fanOut(anyString(), anyString()))
          .thenReturn(new FanoutService.Result("t", FanoutService.Outcome.FANNED_OUT, 1));

      consumer().pollOnce();

      verify(checkpoints).save(StreamCheckpointItem.GROUP_FANOUT, SHARD, "2");
    }

    @Test
    @DisplayName("writes no checkpoint when the shard had nothing")
    void noRecordsNoCheckpoint() {
      oneShard();
      when(streams.getRecords(any(GetRecordsRequest.class)))
          .thenReturn(GetRecordsResponse.builder().records(List.of()).build());

      assertThat(consumer().pollOnce()).isZero();
      verify(checkpoints, never()).save(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("resumes after the stored sequence number")
    void resumesFromCheckpoint() {
      oneShard();
      when(checkpoints.lastSequenceNumber(StreamCheckpointItem.GROUP_FANOUT, SHARD))
          .thenReturn(Optional.of("42"));
      when(streams.getRecords(any(GetRecordsRequest.class)))
          .thenReturn(GetRecordsResponse.builder().records(List.of()).build());

      consumer().pollOnce();

      ArgumentCaptor<GetShardIteratorRequest> captor =
          ArgumentCaptor.forClass(GetShardIteratorRequest.class);
      verify(streams).getShardIterator(captor.capture());
      assertThat(captor.getValue().shardIteratorType())
          .isEqualTo(ShardIteratorType.AFTER_SEQUENCE_NUMBER);
      assertThat(captor.getValue().sequenceNumber()).isEqualTo("42");
    }

    @Test
    @DisplayName("starts a never-read shard at TRIM_HORIZON, not LATEST")
    void startsAtTrimHorizon() {
      oneShard();
      when(checkpoints.lastSequenceNumber(anyString(), anyString())).thenReturn(Optional.empty());
      when(streams.getRecords(any(GetRecordsRequest.class)))
          .thenReturn(GetRecordsResponse.builder().records(List.of()).build());

      consumer().pollOnce();

      // LATEST would silently discard everything written while the consumer was down, and a
      // deploy is exactly when that window opens.
      ArgumentCaptor<GetShardIteratorRequest> captor =
          ArgumentCaptor.forClass(GetShardIteratorRequest.class);
      verify(streams).getShardIterator(captor.capture());
      assertThat(captor.getValue().shardIteratorType()).isEqualTo(ShardIteratorType.TRIM_HORIZON);
    }
  }

  @Nested
  @DisplayName("failure handling")
  class Failures {

    @Test
    @DisplayName("a poison record is skipped and the shard still advances")
    void poisonRecordDoesNotBlockTheShard() {
      oneShard();
      records(
          record(OperationType.INSERT, "1", Map.of("tid", "bad", "aid", "bob")),
          record(OperationType.INSERT, "2", Map.of("tid", "good", "aid", "bob")));
      when(fanout.fanOut(eq("bad"), anyString())).thenThrow(new IllegalStateException("boom"));
      when(fanout.fanOut(eq("good"), anyString()))
          .thenReturn(new FanoutService.Result("good", FanoutService.Outcome.FANNED_OUT, 1));

      consumer().pollOnce();

      // A record this consumer cannot handle will not become handleable by being read again,
      // and blocking on it stalls every later record -- one bad tweet costing everyone else
      // their timeline.
      verify(fanout).fanOut("good", "bob");
      verify(checkpoints).save(StreamCheckpointItem.GROUP_FANOUT, SHARD, "2");
    }

    @Test
    @DisplayName("an expired iterator is re-derived next pass, not treated as a failure")
    void expiredIterator() {
      oneShard();
      when(streams.getRecords(any(GetRecordsRequest.class)))
          .thenThrow(ExpiredIteratorException.builder().message("expired").build());

      // Iterators live 15 minutes and a large fan-out can outlast one. Re-deriving from the
      // checkpoint loses nothing; treating it as fatal would stall the shard permanently.
      assertThat(consumer().pollOnce()).isZero();
      verify(checkpoints, never()).save(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a shard whose iterator cannot be obtained does not stop the other shards")
    void iteratorFailureIsContained() {
      when(streams.describeStream(any(DescribeStreamRequest.class)))
          .thenReturn(
              DescribeStreamResponse.builder()
                  .streamDescription(
                      StreamDescription.builder()
                          .shards(
                              Shard.builder().shardId("broken").build(),
                              Shard.builder().shardId(SHARD).build())
                          .build())
                  .build());
      when(streams.getShardIterator(any(GetShardIteratorRequest.class)))
          .thenThrow(new IllegalStateException("no iterator"))
          .thenReturn(GetShardIteratorResponse.builder().shardIterator("iter").build());
      records(record(OperationType.INSERT, "1", Map.of("tid", "t1", "aid", "bob")));
      when(fanout.fanOut(anyString(), anyString()))
          .thenReturn(new FanoutService.Result("t1", FanoutService.Outcome.FANNED_OUT, 1));

      assertThat(consumer().pollOnce()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("configuration")
  class Configuration {

    @Test
    @DisplayName("does nothing at all without a stream ARN")
    void noArn() {
      FanoutProperties blank =
          new FanoutProperties("", Duration.ofSeconds(1), Duration.ofSeconds(5), 100, 50_000, true);
      // Blank asks the tweets table for its stream; a table that is not there leaves the
      // consumer idle rather than failing to start.
      when(dynamo.describeTable(any(DescribeTableRequest.class)))
          .thenThrow(ResourceNotFoundException.builder().message("no such table").build());

      // Idle rather than guessing: a consumer pointed at the wrong stream is worse than one
      // pointed at none.
      assertThat(consumer(blank).pollOnce()).isZero();
      verify(streams, never()).describeStream(any(DescribeStreamRequest.class));
    }

    @Test
    @DisplayName("re-reads the shard list on every pass")
    void rereadsShards() {
      oneShard();
      when(streams.getRecords(any(GetRecordsRequest.class)))
          .thenReturn(GetRecordsResponse.builder().records(List.of()).build());

      StreamConsumer consumer = consumer();
      consumer.pollOnce();
      consumer.pollOnce();

      // Shards split as throughput rises. A consumer holding a stale list keeps polling a
      // closed shard while records pile up in its successor -- which looks exactly like a
      // working consumer until someone notices the lag.
      verify(streams, org.mockito.Mockito.times(2))
          .describeStream(any(DescribeStreamRequest.class));
    }
  }
}
