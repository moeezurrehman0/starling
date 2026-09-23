/* SPDX-License-Identifier: MIT */
package dev.twitterclone.timeline.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("JsonCache")
class JsonCacheTest {

  private static final Duration TTL = Duration.ofSeconds(30);

  @Mock private StringRedisTemplate redis;
  @Mock private ValueOperations<String, String> values;

  private final ObjectMapper json = new ObjectMapper();

  private JsonCache cache() {
    when(redis.opsForValue()).thenReturn(values);
    return new JsonCache(redis, json, "test");
  }

  @Nested
  @DisplayName("read-through")
  class ReadThrough {

    @Test
    @DisplayName("returns the cached value without calling the loader")
    void hit() {
      when(values.get("k")).thenReturn("[\"a\"]");
      AtomicInteger calls = new AtomicInteger();

      List<String> result =
          cache()
              .get(
                  "k",
                  new TypeReference<List<String>>() {},
                  TTL,
                  () -> {
                    calls.incrementAndGet();
                    return List.of("loaded");
                  });

      assertThat(result).containsExactly("a");
      assertThat(calls).hasValue(0);
    }

    @Test
    @DisplayName("loads and stores on a miss")
    void miss() {
      when(values.get("k")).thenReturn(null);

      List<String> result =
          cache().get("k", new TypeReference<List<String>>() {}, TTL, () -> List.of("loaded"));

      assertThat(result).containsExactly("loaded");
      verify(values).set(eq("k"), eq("[\"loaded\"]"), eq(TTL));
    }
  }

  @Nested
  @DisplayName("degrading rather than failing")
  class Degrading {

    @Test
    @DisplayName("an unreachable Redis becomes a miss, not an error")
    void readFailure() {
      // The property that matters: a cache exists to make the slow path rarer, so losing it
      // must degrade into the slow path. Propagating here would make Redis a hard dependency
      // of every timeline render -- exactly the coupling caching is meant to remove.
      when(values.get("k")).thenThrow(new RedisConnectionFailureException("down"));

      assertThat(cache().get("k", new TypeReference<List<String>>() {}, TTL, () -> List.of("x")))
          .containsExactly("x");
    }

    @Test
    @DisplayName("an undecodable value becomes a miss, not an error")
    void poisonedKey() {
      // Otherwise one bad key, perhaps written by a previous version of this service, breaks
      // every read that touches it until somebody deletes it by hand.
      when(values.get("k")).thenReturn("{not json");

      assertThat(cache().read("k", new TypeReference<List<String>>() {})).isEmpty();
    }

    @Test
    @DisplayName("a failed write is swallowed")
    void writeFailure() {
      when(values.get("k")).thenReturn(null);
      org.mockito.Mockito.doThrow(new RedisConnectionFailureException("down"))
          .when(values)
          .set(anyString(), anyString(), any(Duration.class));

      // The caller already holds the value it was going to return; there is nothing to recover.
      assertThat(cache().get("k", new TypeReference<List<String>>() {}, TTL, () -> List.of("x")))
          .containsExactly("x");
    }

    @Test
    @DisplayName("read does not call the loader")
    void readIsNotReadThrough() {
      when(values.get("k")).thenReturn(null);

      assertThat(cache().read("k", new TypeReference<List<String>>() {})).isEmpty();
      verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }
  }
}
