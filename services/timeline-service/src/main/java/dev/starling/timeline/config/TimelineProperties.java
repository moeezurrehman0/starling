/* SPDX-License-Identifier: MIT */
package dev.starling.timeline.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything the read path needs that is not a table or a cache connection.
 *
 * @param userServiceUrl base URL of user-service, source of the follow graph
 * @param tweetServiceUrl base URL of tweet-service, source of tweet bodies
 * @param celebrityFeedSize how many recent tweets to pull per celebrity
 * @param celebrityFeedTtl how long a celebrity's recent-tweet list stays cached
 * @param followeesTtl how long a user's celebrity-followee list stays cached
 * @param tweetTtl how long a hydrated tweet body stays cached
 */
@ConfigurationProperties("starling.timeline")
public record TimelineProperties(
    @DefaultValue("http://localhost:8081") String userServiceUrl,
    @DefaultValue("http://localhost:8082") String tweetServiceUrl,
    @DefaultValue("50") int celebrityFeedSize,
    @DefaultValue("30s") Duration celebrityFeedTtl,
    @DefaultValue("5m") Duration followeesTtl,
    @DefaultValue("60s") Duration tweetTtl) {}
