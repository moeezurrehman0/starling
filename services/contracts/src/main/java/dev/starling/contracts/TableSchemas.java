/* SPDX-License-Identifier: MIT */
package dev.starling.contracts;

import java.time.Instant;
import software.amazon.awssdk.enhanced.dynamodb.EnhancedType;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.mapper.StaticAttributeTags;
import software.amazon.awssdk.enhanced.dynamodb.mapper.StaticImmutableTableSchema;

/**
 * The single declaration of how each item record maps onto stored DynamoDB attributes.
 *
 * <p>One schema per table, declared once and shared, so that a writer in one service and a reader
 * in another cannot disagree about an attribute name. See the package documentation for why that
 * disagreement would otherwise be silent.
 *
 * <p>Stored names are two or three characters. DynamoDB charges for the attribute name on every
 * item, so {@code retweetOfTweetId} is stored as {@code rt}; at a hundred million timeline entries
 * the difference is not cosmetic. The cost of this is that a raw table scan in the AWS console is
 * unreadable without this file open beside it, which is a trade this project accepts and records
 * rather than rediscovers.
 *
 * <p>Table <em>names</em> are deliberately absent. Every environment prefixes them ({@code
 * sandbox-tweets}, {@code prod-tweets}), and resolving that prefix needs configuration, which is
 * exactly the behaviour this module refuses to carry. Services resolve the name and pass it to
 * {@code DynamoDbEnhancedClient.table(name, TableSchemas.TWEET)}.
 *
 * <p>Renaming any string in this file is a breaking change to a published contract and must go
 * through expand–contract: add the new attribute, dual-write, backfill, migrate readers, then drop
 * the old one. The round-trip tests assert every stored name literally so that a rename cannot be
 * made casually.
 */
public final class TableSchemas {

  private TableSchemas() {}

  /** Schema for the {@code users} table: profile and follower state, keyed by user id. */
  public static final TableSchema<UserItem> USER = userSchema();

  /** Schema for the {@code handles} table: the uniqueness index over {@code @handle}. */
  public static final TableSchema<HandleItem> HANDLE = handleSchema();

  /** Schema for the {@code tweets} table: the authoritative tweet store and stream source. */
  public static final TableSchema<TweetItem> TWEET = tweetSchema();

  /** Schema for the {@code follows} table: the follow graph, queryable in both directions. */
  public static final TableSchema<FollowItem> FOLLOW = followSchema();

  /** Schema for the {@code timelines} table: materialised home timelines, TTL-expired. */
  public static final TableSchema<TimelineEntryItem> TIMELINE_ENTRY = timelineEntrySchema();

  /** Schema for the {@code likes} table: one row per (tweet, liker). */
  public static final TableSchema<LikeItem> LIKE = likeSchema();

  /** Schema for the {@code idempotency} table: claimed request keys and their outcomes. */
  public static final TableSchema<IdempotencyItem> IDEMPOTENCY = idempotencySchema();

  /** Schema for the {@code stream_checkpoints} table: per-shard consumer progress. */
  public static final TableSchema<StreamCheckpointItem> STREAM_CHECKPOINT = checkpointSchema();

  private static StaticImmutableTableSchema<UserItem, UserItem.Builder> userSchema() {
    return StaticImmutableTableSchema.builder(UserItem.class, UserItem.Builder.class)
        .newItemBuilder(UserItem::builder, UserItem.Builder::build)
        .addAttribute(
            String.class,
            a ->
                a.name("uid")
                    .getter(UserItem::userId)
                    .setter(UserItem.Builder::userId)
                    .tags(StaticAttributeTags.primaryPartitionKey()))
        .addAttribute(
            String.class,
            a -> a.name("h").getter(UserItem::handle).setter(UserItem.Builder::handle))
        .addAttribute(
            String.class,
            a -> a.name("dn").getter(UserItem::displayName).setter(UserItem.Builder::displayName))
        .addAttribute(
            String.class, a -> a.name("bio").getter(UserItem::bio).setter(UserItem.Builder::bio))
        .addAttribute(
            String.class,
            a -> a.name("av").getter(UserItem::avatarUrl).setter(UserItem.Builder::avatarUrl))
        .addAttribute(
            Instant.class,
            a -> a.name("ca").getter(UserItem::createdAt).setter(UserItem.Builder::createdAt))
        .addAttribute(
            Long.class,
            a ->
                a.name("fc")
                    .getter(UserItem::followerCount)
                    .setter(UserItem.Builder::followerCount))
        .addAttribute(
            Boolean.class,
            a -> a.name("celeb").getter(UserItem::celebrity).setter(UserItem.Builder::celebrity))
        .build();
  }

  private static StaticImmutableTableSchema<HandleItem, HandleItem.Builder> handleSchema() {
    return StaticImmutableTableSchema.builder(HandleItem.class, HandleItem.Builder.class)
        .newItemBuilder(HandleItem::builder, HandleItem.Builder::build)
        .addAttribute(
            String.class,
            a ->
                a.name("h")
                    .getter(HandleItem::handle)
                    .setter(HandleItem.Builder::handle)
                    .tags(StaticAttributeTags.primaryPartitionKey()))
        .addAttribute(
            String.class,
            a -> a.name("uid").getter(HandleItem::userId).setter(HandleItem.Builder::userId))
        .addAttribute(
            Instant.class,
            a -> a.name("cla").getter(HandleItem::claimedAt).setter(HandleItem.Builder::claimedAt))
        .build();
  }

  private static StaticImmutableTableSchema<TweetItem, TweetItem.Builder> tweetSchema() {
    return StaticImmutableTableSchema.builder(TweetItem.class, TweetItem.Builder.class)
        .newItemBuilder(TweetItem::builder, TweetItem.Builder::build)
        .addAttribute(
            String.class,
            a ->
                a.name("tid")
                    .getter(TweetItem::tweetId)
                    .setter(TweetItem.Builder::tweetId)
                    .tags(
                        StaticAttributeTags.primaryPartitionKey(),
                        // Also the sort key of author-index. UUIDv7 is lexicographically
                        // ordered by creation time, so sorting the index on the tweet id is
                        // what makes a profile page "newest first" without storing a second
                        // timestamp attribute to sort on.
                        StaticAttributeTags.secondarySortKey(TweetItem.AUTHOR_INDEX)))
        .addAttribute(
            String.class,
            a ->
                a.name("aid")
                    .getter(TweetItem::authorId)
                    .setter(TweetItem.Builder::authorId)
                    .tags(StaticAttributeTags.secondaryPartitionKey(TweetItem.AUTHOR_INDEX)))
        .addAttribute(
            String.class,
            a -> a.name("txt").getter(TweetItem::text).setter(TweetItem.Builder::text))
        .addAttribute(
            EnhancedType.listOf(String.class),
            a -> a.name("mk").getter(TweetItem::mediaKeys).setter(TweetItem.Builder::mediaKeys))
        .addAttribute(
            String.class,
            a ->
                a.name("rp")
                    .getter(TweetItem::replyToTweetId)
                    .setter(TweetItem.Builder::replyToTweetId))
        .addAttribute(
            String.class,
            a ->
                a.name("rt")
                    .getter(TweetItem::retweetOfTweetId)
                    .setter(TweetItem.Builder::retweetOfTweetId))
        .addAttribute(
            Instant.class,
            a -> a.name("ca").getter(TweetItem::createdAt).setter(TweetItem.Builder::createdAt))
        .addAttribute(
            Long.class,
            a -> a.name("lc").getter(TweetItem::likeCount).setter(TweetItem.Builder::likeCount))
        .build();
  }

  private static StaticImmutableTableSchema<FollowItem, FollowItem.Builder> followSchema() {
    return StaticImmutableTableSchema.builder(FollowItem.class, FollowItem.Builder.class)
        .newItemBuilder(FollowItem::builder, FollowItem.Builder::build)
        .addAttribute(
            String.class,
            a ->
                a.name("fwr")
                    .getter(FollowItem::followerId)
                    .setter(FollowItem.Builder::followerId)
                    .tags(
                        StaticAttributeTags.primaryPartitionKey(),
                        StaticAttributeTags.secondarySortKey(FollowItem.FOLLOWEE_INDEX)))
        .addAttribute(
            String.class,
            a ->
                a.name("fwe")
                    .getter(FollowItem::followeeId)
                    .setter(FollowItem.Builder::followeeId)
                    .tags(
                        StaticAttributeTags.primarySortKey(),
                        StaticAttributeTags.secondaryPartitionKey(FollowItem.FOLLOWEE_INDEX)))
        .addAttribute(
            Instant.class,
            a -> a.name("ca").getter(FollowItem::createdAt).setter(FollowItem.Builder::createdAt))
        .build();
  }

  private static StaticImmutableTableSchema<TimelineEntryItem, TimelineEntryItem.Builder>
      timelineEntrySchema() {
    return StaticImmutableTableSchema.builder(
            TimelineEntryItem.class, TimelineEntryItem.Builder.class)
        .newItemBuilder(TimelineEntryItem::builder, TimelineEntryItem.Builder::build)
        .addAttribute(
            String.class,
            a ->
                a.name("own")
                    .getter(TimelineEntryItem::ownerId)
                    .setter(TimelineEntryItem.Builder::ownerId)
                    .tags(StaticAttributeTags.primaryPartitionKey()))
        .addAttribute(
            String.class,
            a ->
                a.name("tid")
                    .getter(TimelineEntryItem::tweetId)
                    .setter(TimelineEntryItem.Builder::tweetId)
                    .tags(StaticAttributeTags.primarySortKey()))
        .addAttribute(
            String.class,
            a ->
                a.name("aid")
                    .getter(TimelineEntryItem::authorId)
                    .setter(TimelineEntryItem.Builder::authorId))
        .addAttribute(
            Instant.class,
            a ->
                a.name("ca")
                    .getter(TimelineEntryItem::createdAt)
                    .setter(TimelineEntryItem.Builder::createdAt))
        // "exp" is the attribute named in the table's TimeToLiveSpecification. Renaming it
        // here without changing the Terraform stops expiry silently: DynamoDB looks for an
        // attribute that no longer exists and simply never deletes anything.
        .addAttribute(
            Long.class,
            a ->
                a.name("exp")
                    .getter(TimelineEntryItem::expiresAt)
                    .setter(TimelineEntryItem.Builder::expiresAt))
        .build();
  }

  private static StaticImmutableTableSchema<LikeItem, LikeItem.Builder> likeSchema() {
    return StaticImmutableTableSchema.builder(LikeItem.class, LikeItem.Builder.class)
        .newItemBuilder(LikeItem::builder, LikeItem.Builder::build)
        .addAttribute(
            String.class,
            a ->
                a.name("tid")
                    .getter(LikeItem::tweetId)
                    .setter(LikeItem.Builder::tweetId)
                    .tags(StaticAttributeTags.primaryPartitionKey()))
        .addAttribute(
            String.class,
            a ->
                a.name("uid")
                    .getter(LikeItem::userId)
                    .setter(LikeItem.Builder::userId)
                    .tags(StaticAttributeTags.primarySortKey()))
        .addAttribute(
            Instant.class,
            a -> a.name("la").getter(LikeItem::likedAt).setter(LikeItem.Builder::likedAt))
        .build();
  }

  private static StaticImmutableTableSchema<IdempotencyItem, IdempotencyItem.Builder>
      idempotencySchema() {
    return StaticImmutableTableSchema.builder(IdempotencyItem.class, IdempotencyItem.Builder.class)
        .newItemBuilder(IdempotencyItem::builder, IdempotencyItem.Builder::build)
        .addAttribute(
            String.class,
            a ->
                a.name("k")
                    .getter(IdempotencyItem::key)
                    .setter(IdempotencyItem.Builder::key)
                    .tags(StaticAttributeTags.primaryPartitionKey()))
        .addAttribute(
            String.class,
            a ->
                a.name("rqh")
                    .getter(IdempotencyItem::requestHash)
                    .setter(IdempotencyItem.Builder::requestHash))
        .addAttribute(
            String.class,
            a ->
                a.name("rid")
                    .getter(IdempotencyItem::resultId)
                    .setter(IdempotencyItem.Builder::resultId))
        .addAttribute(
            Integer.class,
            a ->
                a.name("sc")
                    .getter(IdempotencyItem::statusCode)
                    .setter(IdempotencyItem.Builder::statusCode))
        .addAttribute(
            Instant.class,
            a ->
                a.name("ca")
                    .getter(IdempotencyItem::createdAt)
                    .setter(IdempotencyItem.Builder::createdAt))
        .addAttribute(
            Long.class,
            a ->
                a.name("exp")
                    .getter(IdempotencyItem::expiresAt)
                    .setter(IdempotencyItem.Builder::expiresAt))
        .build();
  }

  private static StaticImmutableTableSchema<StreamCheckpointItem, StreamCheckpointItem.Builder>
      checkpointSchema() {
    return StaticImmutableTableSchema.builder(
            StreamCheckpointItem.class, StreamCheckpointItem.Builder.class)
        .newItemBuilder(StreamCheckpointItem::builder, StreamCheckpointItem.Builder::build)
        .addAttribute(
            String.class,
            a ->
                a.name("cg")
                    .getter(StreamCheckpointItem::consumerGroup)
                    .setter(StreamCheckpointItem.Builder::consumerGroup)
                    .tags(StaticAttributeTags.primaryPartitionKey()))
        .addAttribute(
            String.class,
            a ->
                a.name("sh")
                    .getter(StreamCheckpointItem::shardId)
                    .setter(StreamCheckpointItem.Builder::shardId)
                    .tags(StaticAttributeTags.primarySortKey()))
        // Sequence numbers are up to 40 digits and are compared lexicographically only after
        // zero-padding, so they are stored as strings. Storing them as N would silently lose
        // precision beyond a long.
        .addAttribute(
            String.class,
            a ->
                a.name("sq")
                    .getter(StreamCheckpointItem::sequenceNumber)
                    .setter(StreamCheckpointItem.Builder::sequenceNumber))
        .addAttribute(
            Instant.class,
            a ->
                a.name("ua")
                    .getter(StreamCheckpointItem::updatedAt)
                    .setter(StreamCheckpointItem.Builder::updatedAt))
        .build();
  }
}
