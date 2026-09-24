/* SPDX-License-Identifier: MIT */
package dev.starling.gateway.routing;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Forwards everything it does not own to the service that does.
 *
 * <p>A hand-written proxy rather than Spring Cloud Gateway. That project is reactive and tracks its
 * own release train; pulling it in would put a second web stack and a second Boot compatibility
 * matrix into a system whose gateway needs three behaviours — route, limit, tag. The cost of this
 * decision is visible in this one class, which is the point: a gateway whose behaviour is not
 * readable is a gateway nobody dares change.
 *
 * <p>Virtual threads make the blocking model viable. Each proxied request parks a virtual thread on
 * the upstream call rather than a platform one, so the edge is not sized by its slowest downstream.
 */
@RestController
public class ProxyController {

  private static final Logger LOG = LoggerFactory.getLogger(ProxyController.class);

  /**
   * Headers that describe this connection rather than the message.
   *
   * <p>Forwarding {@code Connection} or {@code Transfer-Encoding} to an upstream means describing
   * the client's hop on a hop that no longer exists; {@code Content-Length} is re-derived by the
   * client and a stale one truncates the body.
   */
  private static final Set<String> HOP_BY_HOP =
      Set.of(
          "connection",
          "keep-alive",
          "proxy-authenticate",
          "proxy-authorization",
          "te",
          "trailer",
          "transfer-encoding",
          "upgrade",
          "content-length",
          "host");

  private final RestClient client;
  private final RouteTable routes;

  public ProxyController(RestClient upstreamClient, RouteTable routes) {
    this.client = upstreamClient;
    this.routes = routes;
  }

  /** Proxies any request the gateway's own controllers did not claim. */
  @RequestMapping("/**")
  public ResponseEntity<byte[]> proxy(HttpServletRequest request) throws IOException {
    String path = request.getRequestURI();
    Optional<String> upstream = routes.upstreamFor(path);
    if (upstream.isEmpty()) {
      LOG.debug("no route for {}", path);
      return ResponseEntity.notFound().build();
    }

    URI target =
        UriComponentsBuilder.fromUriString(upstream.get())
            .path(path)
            .query(request.getQueryString())
            .build(true)
            .toUri();

    RestClient.RequestBodySpec spec =
        client.method(HttpMethod.valueOf(request.getMethod())).uri(target);
    copyRequestHeaders(request, spec);

    byte[] body = request.getInputStream().readAllBytes();
    if (body.length > 0) {
      spec.body(body);
    }

    try {
      ResponseEntity<byte[]> response =
          spec.retrieve()
              // Upstream 4xx and 5xx are results, not failures of the proxy. Letting the
              // default handler throw would turn every downstream 404 into a gateway 500 and
              // erase the only useful information in the response.
              .onStatus(status -> true, (req, res) -> {})
              .toEntity(byte[].class);
      return ResponseEntity.status(response.getStatusCode())
          .headers(sanitise(response.getHeaders()))
          .body(response.getBody());
    } catch (ResourceAccessException e) {
      // The upstream did not answer in time or refused the connection. 504 says that honestly;
      // a 500 would send the caller looking for a bug in a service that never ran.
      LOG.warn("upstream {} unreachable", target, e);
      return ResponseEntity.status(504).build();
    }
  }

  private void copyRequestHeaders(HttpServletRequest request, RestClient.RequestBodySpec spec) {
    request
        .getHeaderNames()
        .asIterator()
        .forEachRemaining(
            name -> {
              if (HOP_BY_HOP.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                return;
              }
              List<String> values = java.util.Collections.list(request.getHeaders(name));
              spec.header(name, values.toArray(String[]::new));
            });
  }

  /**
   * Strips hop-by-hop headers from a response before it is returned to the client.
   *
   * <p>The upstream's {@code Content-Length} and {@code Transfer-Encoding} describe the body as it
   * arrived on that connection. Echoing them onto a different connection is how a proxy truncates a
   * response it copied perfectly.
   *
   * @param upstreamHeaders the headers the upstream sent
   * @return the headers safe to pass on
   */
  private static HttpHeaders sanitise(HttpHeaders upstreamHeaders) {
    HttpHeaders headers = new HttpHeaders();
    upstreamHeaders.forEach(
        (name, values) -> {
          if (!HOP_BY_HOP.contains(name.toLowerCase(java.util.Locale.ROOT))) {
            headers.addAll(name, values);
          }
        });
    return headers;
  }
}
