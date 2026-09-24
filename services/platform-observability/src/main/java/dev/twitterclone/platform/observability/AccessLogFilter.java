/* SPDX-License-Identifier: MIT */
package dev.twitterclone.platform.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Logs one line per HTTP request, after the response is committed.
 *
 * <p>This exists because the services otherwise log nothing at all during normal operation. That is
 * a defensible default -- a handler that logs on the happy path is usually noise -- but it leaves
 * log-to-trace correlation with nothing to correlate: Loki holds startup banners and Tempo holds
 * spans, and no line in the first has a {@code trace_id} pointing into the second. One access line
 * per request is the minimum that makes the link real.
 *
 * <p>The filter runs at the lowest precedence so that {@code TraceWebFilter} has already opened the
 * span and populated the MDC by the time this logs; the {@code trace_id} and {@code span_id} fields
 * are contributed by the MDC, not written here.
 */
public final class AccessLogFilter extends OncePerRequestFilter {

  private static final Logger LOG = LoggerFactory.getLogger("access");

  /**
   * Probe and scrape endpoints are excluded. At a 10-second probe interval across three replicas
   * and two probes, they would be roughly 40 lines a minute per service of traffic that carries no
   * diagnostic value and would dominate any Loki query. The cost of the exclusion is that a failing
   * probe is invisible here -- but a failing probe is already visible as a restarting pod and a
   * {@code TargetDown} alert.
   */
  private static final Set<String> SILENT_PREFIXES =
      Set.of("/actuator/health", "/actuator/prometheus", "/actuator/info");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    long startNanos = System.nanoTime();
    try {
      chain.doFilter(request, response);
    } finally {
      long durationMillis = (System.nanoTime() - startNanos) / 1_000_000L;
      // Logged in the finally block so that a request terminated by an exception still
      // produces a line. The status will be whatever the container has set at that point,
      // which for an unhandled exception is 200 until the error dispatch rewrites it --
      // the line is still worth having, and the exception itself is logged elsewhere.
      LOG.atInfo()
          .addKeyValue("http.method", request.getMethod())
          .addKeyValue("http.path", request.getRequestURI())
          .addKeyValue("http.status", response.getStatus())
          .addKeyValue("duration_ms", durationMillis)
          .log("request");
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Returning true here skips the filter entirely rather than logging and discarding, so the
   * excluded paths cost a set lookup and nothing else.
   */
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    for (String prefix : SILENT_PREFIXES) {
      if (path.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }
}
