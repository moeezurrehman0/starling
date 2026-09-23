/* SPDX-License-Identifier: MIT */
package dev.twitterclone.fanout.persistence;

import dev.twitterclone.contracts.TimelineEntryItem;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchWriteItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.BatchWriteResult;
import software.amazon.awssdk.enhanced.dynamodb.model.WriteBatch;

/**
 * Writes materialised timeline entries in batches.
 *
 * <p>Plain puts, not conditional ones. The key is {@code (ownerId, tweetId)}, so writing the same
 * entry twice produces the same row — which is what makes the at-least-once stream safe to replay.
 * A conditional put would turn every replayed record into a {@code ConditionalCheckFailedException}
 * the caller would have to distinguish from a real failure, buying nothing.
 */
@Repository
public class TimelineWriter {

  private static final Logger LOG = LoggerFactory.getLogger(TimelineWriter.class);

  /** DynamoDB's hard ceiling on items in one BatchWriteItem. */
  private static final int BATCH_LIMIT = 25;

  /** How many times to re-send items DynamoDB declined to write before giving up. */
  private static final int MAX_ATTEMPTS = 5;

  private final DynamoDbEnhancedClient enhanced;
  private final DynamoDbTable<TimelineEntryItem> timelines;

  public TimelineWriter(
      DynamoDbEnhancedClient enhanced, DynamoDbTable<TimelineEntryItem> timelinesTable) {
    this.enhanced = enhanced;
    this.timelines = timelinesTable;
  }

  /**
   * Writes a set of entries, retrying whatever DynamoDB declines.
   *
   * @param entries the entries to write
   * @return how many were written
   */
  public int write(Collection<TimelineEntryItem> entries) {
    List<TimelineEntryItem> pending = new ArrayList<>(entries);
    int written = 0;
    for (int from = 0; from < pending.size(); from += BATCH_LIMIT) {
      written += writeChunk(pending.subList(from, Math.min(from + BATCH_LIMIT, pending.size())));
    }
    return written;
  }

  private int writeChunk(List<TimelineEntryItem> chunk) {
    List<TimelineEntryItem> pending = new ArrayList<>(chunk);
    for (int attempt = 1; attempt <= MAX_ATTEMPTS && !pending.isEmpty(); attempt++) {
      WriteBatch.Builder<TimelineEntryItem> batch =
          WriteBatch.builder(TimelineEntryItem.class).mappedTableResource(timelines);
      pending.forEach(batch::addPutItem);

      BatchWriteResult result =
          enhanced.batchWriteItem(
              BatchWriteItemEnhancedRequest.builder().writeBatches(batch.build()).build());

      // BatchWriteItem does not fail when it is throttled -- it returns the items it declined
      // and a 200. Ignoring unprocessedPutItemsForTable is the classic way to lose writes
      // silently under load, with nothing in the logs to suggest anything happened.
      List<TimelineEntryItem> unprocessed = result.unprocessedPutItemsForTable(timelines);
      if (unprocessed.isEmpty()) {
        return chunk.size();
      }
      LOG.debug("batch write left {} items unprocessed on attempt {}", unprocessed.size(), attempt);
      pending = new ArrayList<>(unprocessed);
    }
    if (!pending.isEmpty()) {
      LOG.warn("gave up on {} timeline entries after {} attempts", pending.size(), MAX_ATTEMPTS);
    }
    return chunk.size() - pending.size();
  }
}
