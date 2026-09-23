/* SPDX-License-Identifier: MIT */
package dev.twitterclone.tweet.config;

import dev.twitterclone.tweet.search.SearchProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the search indexer's settings.
 *
 * <p>No beans of its own: the index repository, the indexer and its runner are all components, and
 * the {@code DataSource}, {@code JdbcClient} and Flyway migration run come from Boot's
 * autoconfiguration. This class exists so that {@link SearchProperties} is bound, which a record
 * annotated with {@code @ConfigurationProperties} is not unless something asks for it.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SearchProperties.class)
public class SearchConfig {}
