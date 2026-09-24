/* SPDX-License-Identifier: MIT */
package dev.starling.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.starling.gateway.config.GatewayProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TokenBucket")
// RedisTemplate.execute is generic in its return type and the mock cannot carry it.
@SuppressWarnings("unchecked")
class TokenBucketTest {

  @Mock private StringRedisTemplate redis;

  private TokenBucket bucket(boolean enabled) {
    return new TokenBucket(
        redis,
        new GatewayProperties(
            Map.of(),
            Duration.ofSeconds(5),
            new GatewayProperties.RateLimit(enabled, 10, 10, Duration.ofSeconds(1))));
  }

  private void redisReturns(Object... values) {
    when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
        .thenReturn(List.of(values));
  }

  @Test
  @DisplayName("allows a request with tokens left")
  void allows() {
    redisReturns(1L, 9L);

    TokenBucket.Decision decision = bucket(true).spend("u:alice");

    assertThat(decision.allowed()).isTrue();
    assertThat(decision.remaining()).isEqualTo(9);
  }

  @Test
  @DisplayName("refuses a request with an empty bucket")
  void refuses() {
    redisReturns(0L, 0L);

    assertThat(bucket(true).spend("u:alice").allowed()).isFalse();
  }

  @Test
  @DisplayName("does not touch Redis when limiting is off")
  void disabled() {
    assertThat(bucket(false).spend("u:alice").allowed()).isTrue();
    verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
  }

  @Test
  @DisplayName("allows the request when Redis is down")
  void failsOpen() {
    when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
        .thenThrow(new QueryTimeoutException("redis is gone"));

    // A rate limiter that can take the site down has inverted its own purpose: the outage it
    // would cause is certain, and the abuse it protects against is occasional.
    assertThat(bucket(true).spend("u:alice").allowed()).isTrue();
  }

  @Test
  @DisplayName("allows the request when the script returns something unexpected")
  void malformedReply() {
    when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
        .thenReturn(List.of(1L));

    assertThat(bucket(true).spend("u:alice").allowed()).isTrue();
  }

  @Test
  @DisplayName("allows the request when the script returns nothing")
  void nullReply() {
    when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(null);

    assertThat(bucket(true).spend("u:alice").allowed()).isTrue();
  }

  @Test
  @DisplayName("keeps both keys of a bucket in one hash slot")
  void keysAreColocated() {
    redisReturns(1L, 9L);

    bucket(true).spend("u:alice");

    // The script touches two keys, and Redis Cluster refuses a multi-key script whose keys
    // hash to different slots. The braces make the caller id the hash tag, so the count and
    // its timestamp always land together.
    org.mockito.ArgumentCaptor<List<String>> keys = org.mockito.ArgumentCaptor.captor();
    verify(redis).execute(any(RedisScript.class), keys.capture(), any(Object[].class));
    assertThat(keys.getValue()).allMatch(key -> key.contains("{u:alice}"));
  }

  @Test
  @DisplayName("reports the configured period for Retry-After")
  void exposesPeriod() {
    assertThat(bucket(true).period()).isEqualTo(Duration.ofSeconds(1));
  }

  @Test
  @DisplayName("different callers get different buckets")
  void perCaller() {
    redisReturns(1L, 9L);
    TokenBucket bucket = bucket(true);

    bucket.spend("u:alice");
    bucket.spend("u:bob");

    org.mockito.ArgumentCaptor<List<String>> keys = org.mockito.ArgumentCaptor.captor();
    verify(redis, org.mockito.Mockito.times(2))
        .execute(any(RedisScript.class), keys.capture(), any(Object[].class));
    assertThat(keys.getAllValues().get(0)).isNotEqualTo(keys.getAllValues().get(1));
  }

  @Test
  @DisplayName("passes burst, refill and period to the script")
  void passesConfiguration() {
    redisReturns(1L, 9L);

    bucket(true).spend("u:alice");

    org.mockito.ArgumentCaptor<Object[]> args = org.mockito.ArgumentCaptor.captor();
    verify(redis).execute(any(RedisScript.class), anyList(), args.capture());
    assertThat(args.getValue()).startsWith("10", "10", "1000");
  }

  @Test
  @DisplayName("an anonymous caller is still bucketed")
  void anonymousKey() {
    redisReturns(1L, 9L);

    assertThat(bucket(true).spend("ip:198.51.100.7").allowed()).isTrue();
    verify(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
  }
}
