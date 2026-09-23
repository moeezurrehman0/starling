/* SPDX-License-Identifier: MIT */
package dev.twitterclone.platform.aws.streams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
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
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DescribeStreamRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeStreamResponse;
import software.amazon.awssdk.services.dynamodb.model.ExpiredIteratorException;
import software.amazon.awssdk.services.dynamodb.model.GetRecordsRequest;
import software.amazon.awssdk.services.dynamodb.model.GetRecordsResponse;
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorRequest;
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorResponse;
import software.amazon.awssdk.services.dynamodb.model.OperationType;
import software.amazon.awssdk.services.dynamodb.model.Record;
import software.amazon.awssdk.services.dynamodb.model.Shard;
import software.amazon.awssdk.services.dynamodb.model.ShardIteratorType;
import software.amazon.awssdk.services.dynamodb.model.StreamDescription;
import software.amazon.awssdk.services.dynamodb.model.StreamRecord;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("StreamReader")
class StreamReaderTest {

  private static final String GROUP = "test-group";
  private static final String SHARD = "shard-1";

  @Mock private DynamoDbStreamsClient streams;
  @Mock private StreamCheckpoints checkpoints;

  private final List<Record> seen = new ArrayList<>();

  private StreamReader reader() {
    return new StreamReader(streams, checkpoints, GROUP, "arn:stream", 100);
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

  private static Record record(String sequence) {
    return Record.builder()
        .eventName(OperationType.INSERT)
        .dynamodb(
            StreamRecord.builder()
                .sequenceNumber(sequence)
                .newImage(Map.of("tid", AttributeValue.fromS("t1")))
                .build())
        .build();
  }

  private void returns(Record... records) {
    when(streams.getRecords(any(GetRecordsRequest.class)))
        .thenReturn(GetRecordsResponse.builder().records(List.of(records)).build());
  }

  @Nested
  @DisplayName("configuration")
  class Configuration {

    @Test
    @DisplayName("does nothing at all when no stream ARN is configured")
    void noArn() {
      StreamReader reader = new StreamReader(streams, checkpoints, GROUP, "", 100);

      assertThat(reader.pollOnce(seen::add)).isZero();
      verify(streams, never()).describeStream(any(DescribeStreamRequest.class));
    }

    @Test
    @DisplayName("re-reads the shard list on every pass, because shards split")
    void shardsAreNotCached() {
      oneShard();
      when(streams.getRecords(any(GetRecordsRequest.class)))
          .thenReturn(GetRecordsResponse.builder().records(List.of()).build());
      StreamReader reader = reader();

      reader.pollOnce(seen::add);
      reader.pollOnce(seen::add);

      verify(streams, org.mockito.Mockito.times(2))
          .describeStream(any(DescribeStreamRequest.class));
    }

    @Test
    @DisplayName("exposes the current shards")
    void exposesShards() {
      oneShard();

      assertThat(reader().shards()).extracting(Shard::shardId).containsExactly(SHARD);
    }
  }

  @Nested
  @DisplayName("iterators")
  class Iterators {

    @Test
    @DisplayName("starts at the trim horizon on a shard never read before")
    void trimHorizonWhenUncheckpointed() {
      oneShard();
      when(checkpoints.lastSequenceNumber(GROUP, SHARD)).thenReturn(Optional.empty());
      returns();

      reader().pollOnce(seen::add);

      ArgumentCaptor<GetShardIteratorRequest> request =
          ArgumentCaptor.forClass(GetShardIteratorRequest.class);
      verify(streams).getShardIterator(request.capture());
      assertThat(request.getValue().shardIteratorType()).isEqualTo(ShardIteratorType.TRIM_HORIZON);
    }

    @Test
    @DisplayName("resumes after the checkpoint when there is one")
    void afterSequenceWhenCheckpointed() {
      oneShard();
      when(checkpoints.lastSequenceNumber(GROUP, SHARD)).thenReturn(Optional.of("42"));
      returns();

      reader().pollOnce(seen::add);

      ArgumentCaptor<GetShardIteratorRequest> request =
          ArgumentCaptor.forClass(GetShardIteratorRequest.class);
      verify(streams).getShardIterator(request.capture());
      assertThat(request.getValue().shardIteratorType())
          .isEqualTo(ShardIteratorType.AFTER_SEQUENCE_NUMBER);
      assertThat(request.getValue().sequenceNumber()).isEqualTo("42");
    }

    @Test
    @DisplayName("gives up on the shard for this pass when an iterator cannot be obtained")
    void iteratorFailureIsNotFatal() {
      oneShard();
      when(streams.getShardIterator(any(GetShardIteratorRequest.class)))
          .thenThrow(new RuntimeException("throttled"));

      assertThat(reader().pollOnce(seen::add)).isZero();
      verify(streams, never()).getRecords(any(GetRecordsRequest.class));
    }

    @Test
    @DisplayName("treats an expired iterator as nothing read, not as a failure")
    void expiredIterator() {
      oneShard();
      when(streams.getRecords(any(GetRecordsRequest.class)))
          .thenThrow(ExpiredIteratorException.builder().message("expired").build());

      assertThat(reader().pollOnce(seen::add)).isZero();
      verify(checkpoints, never()).save(anyString(), anyString(), anyString());
    }
  }

  @Nested
  @DisplayName("checkpointing")
  class Checkpointing {

    @Test
    @DisplayName("checkpoints the last sequence number after the batch")
    void checkpointsAfterBatch() {
      oneShard();
      returns(record("1"), record("2"), record("3"));

      assertThat(reader().pollOnce(seen::add)).isEqualTo(3);
      verify(checkpoints).save(GROUP, SHARD, "3");
    }

    @Test
    @DisplayName("does not checkpoint an empty batch")
    void noCheckpointWhenNothingRead() {
      oneShard();
      returns();

      reader().pollOnce(seen::add);

      verify(checkpoints, never()).save(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("advances past a record the handler could not process")
    void poisonRecordDoesNotStallTheShard() {
      oneShard();
      returns(record("1"), record("2"));

      int processed =
          reader()
              .pollOnce(
                  r -> {
                    seen.add(r);
                    throw new IllegalStateException("bad record");
                  });

      assertThat(processed).isEqualTo(2);
      assertThat(seen).hasSize(2);
      verify(checkpoints).save(GROUP, SHARD, "2");
    }

    @Test
    @DisplayName("partitions progress by consumer group")
    void groupScoped() {
      oneShard();
      returns(record("7"));

      new StreamReader(streams, checkpoints, "other-group", "arn:stream", 100).pollOnce(seen::add);

      verify(checkpoints).save("other-group", SHARD, "7");
      verify(checkpoints).lastSequenceNumber("other-group", SHARD);
    }
  }

  @Nested
  @DisplayName("reading")
  class Reading {

    @Test
    @DisplayName("hands every record to the handler in order")
    void handlesInOrder() {
      oneShard();
      returns(record("1"), record("2"));

      reader().pollOnce(seen::add);

      assertThat(seen).extracting(r -> r.dynamodb().sequenceNumber()).containsExactly("1", "2");
    }

    @Test
    @DisplayName("asks for no more than the configured batch size")
    void honoursBatchSize() {
      oneShard();
      returns();

      new StreamReader(streams, checkpoints, GROUP, "arn:stream", 7).pollOnce(seen::add);

      ArgumentCaptor<GetRecordsRequest> request = ArgumentCaptor.forClass(GetRecordsRequest.class);
      verify(streams).getRecords(request.capture());
      assertThat(request.getValue().limit()).isEqualTo(7);
    }

    @Test
    @DisplayName("drains every shard, not just the first")
    void drainsAllShards() {
      when(streams.describeStream(any(DescribeStreamRequest.class)))
          .thenReturn(
              DescribeStreamResponse.builder()
                  .streamDescription(
                      StreamDescription.builder()
                          .shards(
                              Shard.builder().shardId("a").build(),
                              Shard.builder().shardId("b").build())
                          .build())
                  .build());
      when(streams.getShardIterator(any(GetShardIteratorRequest.class)))
          .thenReturn(GetShardIteratorResponse.builder().shardIterator("iter").build());
      returns(record("1"));

      assertThat(reader().pollOnce(seen::add)).isEqualTo(2);
      verify(checkpoints).save(GROUP, "a", "1");
      verify(checkpoints).save(GROUP, "b", "1");
    }

    @Test
    @DisplayName("reads the stream named in the ARN")
    void readsTheConfiguredStream() {
      oneShard();
      returns();

      reader().pollOnce(seen::add);

      ArgumentCaptor<DescribeStreamRequest> request =
          ArgumentCaptor.forClass(DescribeStreamRequest.class);
      verify(streams).describeStream(request.capture());
      assertThat(request.getValue().streamArn()).isEqualTo("arn:stream");
    }
  }

  @Nested
  @DisplayName("record helpers")
  class Records {

    @Test
    @DisplayName("an insert yields its new image")
    void insertYieldsImage() {
      assertThat(StreamRecords.insertImage(record("1"))).isPresent();
    }

    @Test
    @DisplayName("a modify yields nothing, because likes rewrite the tweet row")
    void modifyYieldsNothing() {
      Record modify =
          Record.builder()
              .eventName(OperationType.MODIFY)
              .dynamodb(
                  StreamRecord.builder()
                      .sequenceNumber("1")
                      .newImage(Map.of("tid", AttributeValue.fromS("t1")))
                      .build())
              .build();

      assertThat(StreamRecords.insertImage(modify)).isEmpty();
    }

    @Test
    @DisplayName("an insert with no image yields nothing")
    void insertWithoutImage() {
      Record bare =
          Record.builder()
              .eventName(OperationType.INSERT)
              .dynamodb(StreamRecord.builder().sequenceNumber("1").build())
              .build();

      assertThat(StreamRecords.insertImage(bare)).isEmpty();
    }

    @Test
    @DisplayName("reads a string attribute, and null for one that is absent")
    void stringAttribute() {
      Map<String, AttributeValue> image = Map.of("tid", AttributeValue.fromS("t1"));

      assertThat(StreamRecords.string(image, "tid")).isEqualTo("t1");
      assertThat(StreamRecords.string(image, "missing")).isNull();
    }

    @Test
    @DisplayName("a non-string attribute reads as null rather than throwing")
    void nonStringAttribute() {
      Map<String, AttributeValue> image = Map.of("lc", AttributeValue.fromN("3"));

      assertThat(StreamRecords.string(image, "lc")).isNull();
    }
  }
}
