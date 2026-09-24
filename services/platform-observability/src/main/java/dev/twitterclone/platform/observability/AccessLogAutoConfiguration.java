/* SPDX-License-Identifier: MIT */
package dev.twitterclone.platform.observability;

import jakarta.servlet.Filter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Registers {@link AccessLogFilter} in any servlet application that has this module on its
 * classpath.
 *
 * <p>Auto-configuration rather than a {@code @Component} scan: the services do not share a base
 * package, so component scanning would require each of them to name this one explicitly, which is
 * the coupling the module exists to remove.
 */
@AutoConfiguration
@ConditionalOnClass(Filter.class)
@ConditionalOnProperty(
    name = "twitterclone.observability.access-log.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AccessLogAutoConfiguration {

  /**
   * Registered at {@link Ordered#LOWEST_PRECEDENCE} so the tracing filter has already opened the
   * span and put {@code traceId} in the MDC. Register it early and every access line would carry an
   * empty trace id -- which looks like working correlation until someone clicks through.
   */
  @Bean
  @ConditionalOnMissingBean
  public FilterRegistrationBean<AccessLogFilter> accessLogFilterRegistration() {
    FilterRegistrationBean<AccessLogFilter> registration =
        new FilterRegistrationBean<>(new AccessLogFilter());
    registration.setOrder(Ordered.LOWEST_PRECEDENCE);
    return registration;
  }
}
