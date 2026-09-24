/* SPDX-License-Identifier: MIT */
package dev.starling.gateway.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies the token bucket to every request that reaches the proxy.
 *
 * <p>Keyed by JWT subject when there is one and by client address otherwise. Keying everything by
 * address would put every user behind one corporate NAT in a single bucket; keying everything by
 * subject would leave the unauthenticated endpoints — sign-up and log-in, the two worth attacking —
 * unlimited.
 *
 * <p>Runs after authentication so the subject is available, which is why it is ordered explicitly
 * rather than registered as a plain bean.
 */
@Component
@Order(RateLimitFilter.ORDER)
public class RateLimitFilter extends OncePerRequestFilter {

  /** After Spring Security's chain, so the principal exists. */
  public static final int ORDER = org.springframework.core.Ordered.LOWEST_PRECEDENCE - 100;

  private final TokenBucket bucket;

  public RateLimitFilter(TokenBucket bucket) {
    this.bucket = bucket;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    TokenBucket.Decision decision = bucket.spend(callerKey(request));
    response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));

    if (!decision.allowed()) {
      response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
      response.setHeader("Retry-After", String.valueOf(bucket.period().toSeconds()));
      response.setContentType("application/problem+json");
      response
          .getWriter()
          .write(
              """
              {"type":"about:blank","title":"Too Many Requests","status":429,\
              "detail":"Rate limit exceeded"}""");
      return;
    }
    chain.doFilter(request, response);
  }

  /** Health and metrics are exempt: a throttled probe would be read as a dead pod. */
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return request.getRequestURI().startsWith("/actuator");
  }

  private static String callerKey(HttpServletRequest request) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.isAuthenticated()
        && authentication.getPrincipal() instanceof Jwt jwt) {
      return "u:" + jwt.getSubject();
    }
    return "ip:" + clientAddress(request);
  }

  private static String clientAddress(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded == null || forwarded.isBlank()) {
      return request.getRemoteAddr();
    }
    // The left-most entry is the original client; everything after it was added by a proxy.
    // Trusting the right-most would bucket every caller under the load balancer.
    int comma = forwarded.indexOf(',');
    return (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
  }
}
