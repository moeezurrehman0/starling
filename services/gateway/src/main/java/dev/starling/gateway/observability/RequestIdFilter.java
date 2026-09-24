/* SPDX-License-Identifier: MIT */
package dev.starling.gateway.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an identity before anything else touches it.
 *
 * <p>Distinct from the trace id. A trace is sampled and lives in the tracing backend; this id is in
 * the response header and in every log line, which is what makes a user's bug report — "it failed,
 * here is the id" — answerable without asking them to reproduce it.
 *
 * <p>Ordered first deliberately, so that a request rejected by authentication or the rate limiter
 * still carries one. An id that only exists for successful requests documents the cases nobody
 * needed to investigate.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

  /** The header carrying the id, in and out. */
  public static final String HEADER = "X-Request-Id";

  /** The MDC key, which the ECS log format picks up automatically. */
  public static final String MDC_KEY = "request.id";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    // An inbound id is honoured rather than replaced. The caller may be another service, or a
    // browser retrying, and generating a fresh id would break the one link between the two
    // halves of the same logical request.
    String id = request.getHeader(HEADER);
    if (id == null || id.isBlank()) {
      id = UUID.randomUUID().toString();
    }

    MDC.put(MDC_KEY, id);
    response.setHeader(HEADER, id);
    try {
      chain.doFilter(request, response);
    } finally {
      // Virtual threads are pooled by the carrier, not the task, but MDC is a thread-local
      // either way: leaving the id behind attributes the next request's logs to this one.
      MDC.remove(MDC_KEY);
    }
  }
}
