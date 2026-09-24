/* SPDX-License-Identifier: MIT */
package dev.starling.tweet.config;

import dev.starling.contracts.StreamCheckpointItem;
import dev.starling.platform.aws.streams.StreamHealthIndicator;
import dev.starling.platform.aws.streams.StreamMetrics;
import dev.starling.tweet.search.SearchProperties;
import dev.starling.tweet.search.TweetIndexer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the search indexer's settings, and its readiness when it is switched on.
 *
 * <p>The index repository, the indexer and its runner are all components, and the {@code
 * DataSource}, {@code JdbcClient} and Flyway migration run come from Boot's autoconfiguration. This
 * class exists so that {@link SearchProperties} is bound, which a record annotated with
 * {@code @ConfigurationProperties} is not unless something asks for it.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SearchProperties.class)
public class SearchConfig {

  /**
   * Readiness for the tweets stream, and only where the stream matters.
   *
   * <p>This image runs as two Deployments: the request-serving {@code tweet-service}, where the
   * indexer is off, and the single-replica {@code tweet-indexer}, where it is on. The indicator is
   * registered in both but only reports out of service in the second. Letting an undiscoverable
   * stream fail readiness on the request-serving replicas would turn an indexing problem into a
   * total write outage.
   *
   * @param indexer the indexer whose stream is reported
   * @param properties supplies whether the indexer is switched on
   * @return the indicator, named {@code stream} in the health response
   */
  @Bean("stream")
  public StreamHealthIndicator streamHealthIndicator(
      TweetIndexer indexer, SearchProperties properties) {
    return new StreamHealthIndicator(indexer.streamSource(), properties.enabled());
  }

  /**
   * The same stream state as a metric.
   *
   * <p>Registered on the request-serving Deployment too, where it will read 0 forever because that
   * process never resolves a stream it does not use. The tag set is what makes that harmless: the
   * alert selects on the {@code tweet-indexer} application, and the series from the other
   * Deployment is a visible, explainable zero rather than a gap that looks like a scrape failure.
   *
   * @param indexer the indexer whose stream is reported
   * @return the binder
   */
  @Bean
  public StreamMetrics streamMetrics(TweetIndexer indexer) {
    return new StreamMetrics(indexer.streamSource(), StreamCheckpointItem.GROUP_SEARCH);
  }
}
