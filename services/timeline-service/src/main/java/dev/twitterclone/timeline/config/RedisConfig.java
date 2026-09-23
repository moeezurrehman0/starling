/* SPDX-License-Identifier: MIT */
package dev.twitterclone.timeline.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Two Redis instances, not one.
 *
 * <p>This is the deliberate centre of the design. A celebrity's recent-tweet list is read by every
 * one of their followers — potentially millions of reads against a single key — while a normal
 * user's cached data is read by roughly one person. Those two workloads want opposite things from
 * an eviction policy, and putting them in one instance means the wrong one wins.
 *
 * <p>The main tier runs {@code allkeys-lru}: its contents are individually cheap to rebuild, so
 * evicting the coldest key under pressure costs one cache miss. The celebrity tier runs {@code
 * volatile-ttl} and is sized small: evicting a hot celebrity entry does not cost one miss, it
 * redirects every follower of that account straight at DynamoDB at the same instant. Isolating the
 * instances is what stops a flood of ordinary traffic from evicting the few keys whose absence is
 * expensive.
 *
 * <p>Both templates are {@link StringRedisTemplate}. Values are JSON encoded by hand rather than by
 * a configured serializer, because the cache is crossed by more than one service in later phases
 * and a Java-specific serialisation format would quietly make it single-language.
 */
@Configuration(proxyBeanMethods = false)
public class RedisConfig {

  /** Connection settings for the main tier, bound from {@code spring.data.redis}. */
  @Bean
  @Primary
  @ConfigurationProperties("spring.data.redis")
  public DataRedisProperties mainDataRedisProperties() {
    return new DataRedisProperties();
  }

  /** Connection settings for the celebrity tier, bound from {@code twitterclone.redis-celeb}. */
  @Bean
  @ConfigurationProperties("twitterclone.redis-celeb")
  public DataRedisProperties celebDataRedisProperties() {
    return new DataRedisProperties();
  }

  @Bean
  @Primary
  public LettuceConnectionFactory mainRedisConnectionFactory(
      @Qualifier("mainDataRedisProperties") DataRedisProperties properties) {
    return factory(properties);
  }

  @Bean
  public LettuceConnectionFactory celebRedisConnectionFactory(
      @Qualifier("celebDataRedisProperties") DataRedisProperties properties) {
    return factory(properties);
  }

  @Bean
  @Primary
  public StringRedisTemplate mainRedis(
      @Qualifier("mainRedisConnectionFactory") RedisConnectionFactory factory) {
    return new StringRedisTemplate(factory);
  }

  @Bean
  public StringRedisTemplate celebRedis(
      @Qualifier("celebRedisConnectionFactory") RedisConnectionFactory factory) {
    return new StringRedisTemplate(factory);
  }

  private static LettuceConnectionFactory factory(DataRedisProperties properties) {
    RedisStandaloneConfiguration standalone =
        new RedisStandaloneConfiguration(properties.getHost(), properties.getPort());
    standalone.setDatabase(properties.getDatabase());
    if (properties.getUsername() != null) {
      standalone.setUsername(properties.getUsername());
    }
    if (properties.getPassword() != null) {
      standalone.setPassword(properties.getPassword());
    }
    return new LettuceConnectionFactory(standalone);
  }
}
