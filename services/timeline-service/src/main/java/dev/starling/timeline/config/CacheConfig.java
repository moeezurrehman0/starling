/* SPDX-License-Identifier: MIT */
package dev.starling.timeline.config;

import dev.starling.timeline.domain.JsonCache;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

/** Names the two cache tiers so the wrong one cannot be injected by accident. */
@Configuration(proxyBeanMethods = false)
public class CacheConfig {

  /** Normal-user data: individually cheap to rebuild, evicted by LRU. */
  @Bean
  public JsonCache mainCache(@Qualifier("mainRedis") StringRedisTemplate redis, ObjectMapper json) {
    return new JsonCache(redis, json, "main");
  }

  /** Celebrity data: few keys, enormously hot, evicted by nearest TTL. */
  @Bean
  public JsonCache celebCache(
      @Qualifier("celebRedis") StringRedisTemplate redis, ObjectMapper json) {
    return new JsonCache(redis, json, "celeb");
  }
}
