/* SPDX-License-Identifier: MIT */
package dev.starling.gateway.ratelimit;

import dev.starling.gateway.config.GatewayProperties;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * A Redis token bucket, refilled lazily.
 *
 * <p>In Lua, and therefore atomic. Read-modify-write from Java would let two requests on two pods
 * read the same count and both decide they were under the limit, which is the failure mode that
 * makes a distributed rate limiter worse than none: it is enforced just tightly enough that nobody
 * checks whether it works.
 *
 * <p>Lazily refilled, rather than by a background tick. A key that nobody touches costs nothing and
 * expires on its own; a refill job would keep every bucket that ever existed alive forever.
 */
@Component
public class TokenBucket {

  private static final Logger LOG = LoggerFactory.getLogger(TokenBucket.class);

  /**
   * Refill by elapsed time, spend one token, return what is left.
   *
   * <p>The TTL is reset on every call to two full refill windows, so a bucket outlives the gap
   * between a caller's requests but not the caller.
   */
  private static final String SCRIPT =
      """
      local tokens_key = KEYS[1]
      local ts_key = KEYS[2]
      local burst = tonumber(ARGV[1])
      local refill = tonumber(ARGV[2])
      local period = tonumber(ARGV[3])
      local now = tonumber(ARGV[4])

      local tokens = tonumber(redis.call('get', tokens_key))
      local last = tonumber(redis.call('get', ts_key))
      if tokens == nil then
        tokens = burst
        last = now
      end

      local elapsed = math.max(0, now - last)
      local gained = math.floor(elapsed / period) * refill
      if gained > 0 then
        tokens = math.min(burst, tokens + gained)
        last = last + math.floor(elapsed / period) * period
      end

      local allowed = 0
      if tokens > 0 then
        tokens = tokens - 1
        allowed = 1
      end

      local ttl = math.ceil((period * 2) / 1000)
      redis.call('set', tokens_key, tokens, 'EX', ttl)
      redis.call('set', ts_key, last, 'EX', ttl)
      return { allowed, tokens }
      """;

  /** The script returns a two-element array, which Lettuce surfaces as a {@code List}. */
  @SuppressWarnings("unchecked")
  private static final Class<List<Object>> RESULT_TYPE =
      (Class<List<Object>>) (Class<?>) List.class;

  private final StringRedisTemplate redis;
  private final GatewayProperties.RateLimit limit;
  private final RedisScript<List<Object>> script;

  public TokenBucket(StringRedisTemplate redis, GatewayProperties properties) {
    this.redis = redis;
    this.limit = properties.rateLimit();
    this.script = RedisScript.of(SCRIPT, RESULT_TYPE);
  }

  /**
   * Spends one token for a caller.
   *
   * @param key who is being limited
   * @return the decision, and how many tokens they have left
   */
  public Decision spend(String key) {
    if (!limit.enabled()) {
      return new Decision(true, limit.burst());
    }
    try {
      List<?> result =
          redis.execute(
              script,
              List.of("rl:{" + key + "}:t", "rl:{" + key + "}:ts"),
              String.valueOf(limit.burst()),
              String.valueOf(limit.refill()),
              String.valueOf(limit.period().toMillis()),
              String.valueOf(System.currentTimeMillis()));
      if (result == null || result.size() < 2) {
        return new Decision(true, limit.burst());
      }
      long allowed = ((Number) result.get(0)).longValue();
      long remaining = ((Number) result.get(1)).longValue();
      return new Decision(allowed == 1, remaining);
    } catch (RuntimeException e) {
      // Fail open. A rate limiter is a protection, not a dependency: making every request
      // depend on Redis being up converts a cache outage into a total outage, and the thing
      // it was protecting against is by definition the rarer event.
      LOG.warn("rate limit check failed, allowing request", e);
      return new Decision(true, limit.burst());
    }
  }

  /** The configured window, for the {@code Retry-After} header. */
  public Duration period() {
    return limit.period();
  }

  /**
   * The outcome of spending a token.
   *
   * @param allowed whether the request may proceed
   * @param remaining tokens left in the bucket
   */
  public record Decision(boolean allowed, long remaining) {}
}
