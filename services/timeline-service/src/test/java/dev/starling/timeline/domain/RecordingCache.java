/* SPDX-License-Identifier: MIT */
package dev.starling.timeline.domain;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * A cache that never hits, recording what it was asked to store.
 *
 * <p>Mocking {@link JsonCache} would mean the client tests assert on cache calls rather than on
 * HTTP behaviour. This stub lets every call fall through to the real request while still making the
 * write visible, so a test can check <em>which tier</em> a client chose — which is the design
 * decision worth protecting here.
 */
final class RecordingCache extends JsonCache {

  private final java.util.Map<String, Object> stored = new java.util.LinkedHashMap<>();
  private final java.util.Map<String, Object> primed = new java.util.LinkedHashMap<>();

  RecordingCache(String name) {
    super(new org.springframework.data.redis.core.StringRedisTemplate(), new ObjectMapper(), name);
  }

  void prime(String key, Object value) {
    primed.put(key, value);
  }

  java.util.Map<String, Object> stored() {
    return stored;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> Optional<T> read(String key, TypeReference<T> type) {
    return Optional.ofNullable((T) primed.get(key));
  }

  @Override
  public <T> T get(String key, TypeReference<T> type, Duration ttl, Supplier<T> loader) {
    Optional<T> hit = read(key, type);
    if (hit.isPresent()) {
      return hit.get();
    }
    T loaded = loader.get();
    put(key, ttl, loaded);
    return loaded;
  }

  @Override
  public void put(String key, Duration ttl, Object value) {
    stored.put(key, value);
  }
}
