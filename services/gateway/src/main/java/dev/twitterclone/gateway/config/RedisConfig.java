/* SPDX-License-Identifier: MIT */
package dev.twitterclone.gateway.config;

import org.springframework.context.annotation.Configuration;

/**
 * Deliberately empty.
 *
 * <p>The gateway uses only the main Redis tier, and only for rate limit counters, so Boot's
 * autoconfiguration from {@code spring.data.redis} is exactly right. The celebrity tier exists to
 * stop a long tail of tweet bodies evicting a small set of expensive keys; rate limit buckets are
 * neither, and putting them on their own instance would add an operational dependency to protect
 * data that is correct to lose.
 *
 * <p>Kept as a file rather than deleted because the absence of a second tier here is a decision,
 * and a decision with no record is indistinguishable from an oversight.
 */
@Configuration(proxyBeanMethods = false)
public class RedisConfig {}
