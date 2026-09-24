/* SPDX-License-Identifier: MIT */
package dev.starling.tweet.search;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tuning for the search indexer.
 *
 * <p>{@code enabled} defaults to false, unlike fan-out's. Fan-out is the whole reason its service
 * exists, so a disabled fan-out worker is a misconfiguration; the indexer is a background job
 * inside a request-serving service, and a tweet-service running without a stream ARN — a unit test,
 * a local run with no LocalStack — should serve requests rather than log a failed poll every five
 * seconds.
 *
 * @param streamArn the {@code tweets} stream to read
 * @param pollInterval how long to wait before asking a shard for records again
 * @param idleBackoff how long to wait after a poll that returned nothing
 * @param batchSize records requested per {@code GetRecords} call
 * @param enabled whether the indexer runs at all
 */
@ConfigurationProperties("starling.search")
public record SearchProperties(
    @DefaultValue("") String streamArn,
    @DefaultValue("2s") Duration pollInterval,
    @DefaultValue("10s") Duration idleBackoff,
    @DefaultValue("100") int batchSize,
    @DefaultValue("false") boolean enabled) {}
