/* SPDX-License-Identifier: MIT */
package dev.twitterclone.timeline.domain;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * A read-through JSON cache over one Redis instance that never fails a request.
 *
 * <p>Every Redis interaction here is wrapped. That is the whole point of the class: a cache exists
 * to make the slow path rarer, so a cache that is down must degrade into the slow path, not into an
 * error. Letting a a Redis connection failure escape would mean losing Redis takes the timeline
 * down entirely — turning an optimisation into a hard dependency, which is exactly the coupling
 * caching is supposed to remove.
 *
 * <p>Values are stored as JSON strings rather than serialised Java objects so the same keys remain
 * readable by other services and by {@code redis-cli} during an incident.
 */
public class JsonCache {

  private static final Logger LOG = LoggerFactory.getLogger(JsonCache.class);

  private final StringRedisTemplate redis;
  private final ObjectMapper json;
  private final String name;

  /**
   * Creates a cache over one Redis instance.
   *
   * @param redis the connection to use — main or celebrity tier
   * @param json the mapper, shared with the rest of the application
   * @param name a short label used only in log lines, to tell the tiers apart
   */
  public JsonCache(StringRedisTemplate redis, ObjectMapper json, String name) {
    this.redis = redis;
    this.json = json;
    this.name = name;
  }

  /**
   * Returns the cached value, or computes and stores it.
   *
   * @param key the cache key
   * @param type the value's type, captured to survive erasure on the way back out
   * @param ttl how long a freshly computed value should live
   * @param loader computes the value on a miss
   * @param <T> the cached type
   * @return the cached or freshly loaded value
   */
  public <T> T get(String key, TypeReference<T> type, Duration ttl, Supplier<T> loader) {
    Optional<T> hit = read(key, type);
    if (hit.isPresent()) {
      return hit.get();
    }
    T loaded = loader.get();
    put(key, ttl, loaded);
    return loaded;
  }

  /**
   * Reads a value without computing one on a miss.
   *
   * @param key the cache key
   * @param type the value's type
   * @param <T> the cached type
   * @return the value, or empty on a miss, a decode failure or an unreachable Redis
   */
  public <T> Optional<T> read(String key, TypeReference<T> type) {
    try {
      String raw = redis.opsForValue().get(key);
      return raw == null ? Optional.empty() : Optional.of(json.readValue(raw, type));
    } catch (RuntimeException e) {
      // A decode failure is treated as a miss on purpose. The alternative -- failing the
      // request -- means one poisoned key, perhaps left by a previous version of this
      // service, breaks every read that touches it until somebody deletes it by hand.
      LOG.warn("{} cache read failed for {}, treating as a miss", name, key, e);
      return Optional.empty();
    }
  }

  /**
   * Stores a value with an expiry.
   *
   * @param key the cache key
   * @param ttl how long it should live
   * @param value the value to store
   */
  public void put(String key, Duration ttl, Object value) {
    try {
      redis.opsForValue().set(key, json.writeValueAsString(value), ttl);
    } catch (RuntimeException e) {
      // Nothing to recover: the caller already has the value it was going to return.
      LOG.warn("{} cache write failed for {}", name, key, e);
    }
  }
}
