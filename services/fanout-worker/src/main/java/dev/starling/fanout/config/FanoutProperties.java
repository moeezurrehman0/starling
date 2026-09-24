/* SPDX-License-Identifier: MIT */
package dev.starling.fanout.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tuning for the stream consumer.
 *
 * @param streamArn the {@code tweets} stream to read, discovered from the table when blank
 * @param pollInterval how long to wait before asking a shard for records again
 * @param idleBackoff how long to wait after a poll that returned nothing
 * @param batchSize records requested per {@code GetRecords} call
 * @param maxFollowersPerTweet safety ceiling on how many timelines one tweet may be written to
 * @param enabled whether the poller runs at all
 */
@ConfigurationProperties("starling.fanout")
public record FanoutProperties(
    @DefaultValue("") String streamArn,
    @DefaultValue("1s") Duration pollInterval,
    @DefaultValue("5s") Duration idleBackoff,
    @DefaultValue("100") int batchSize,
    @DefaultValue("50000") int maxFollowersPerTweet,
    @DefaultValue("true") boolean enabled) {}
