/* SPDX-License-Identifier: MIT */
package dev.twitterclone.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.twitterclone.gateway.config.GatewayProperties;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("ProxyController")
class ProxyControllerTest {

  private static final String UPSTREAM = "http://tweets.internal";

  private MockRestServiceServer server;
  private ProxyController controller;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    controller = new ProxyController(builder.build(), routes(UPSTREAM));
  }

  private static RouteTable routes(String upstream) {
    return new RouteTable(
        new GatewayProperties(
            Map.of("/v1/tweets", upstream),
            Duration.ofSeconds(2),
            new GatewayProperties.RateLimit(false, 1, 1, Duration.ofSeconds(1))));
  }

  private static MockHttpServletRequest request(String httpMethod, String uri) {
    MockHttpServletRequest request = new MockHttpServletRequest(httpMethod, uri);
    request.setRequestURI(uri);
    return request;
  }

  @Test
  @DisplayName("returns the upstream's body and status unchanged")
  void passesThrough() throws Exception {
    server
        .expect(requestTo(UPSTREAM + "/v1/tweets/t1"))
        .andRespond(withSuccess("{\"id\":\"t1\"}", MediaType.APPLICATION_JSON));

    ResponseEntity<byte[]> response = controller.proxy(request("GET", "/v1/tweets/t1"));

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(new String(response.getBody())).isEqualTo("{\"id\":\"t1\"}");
    server.verify();
  }

  @Test
  @DisplayName("passes an upstream error through rather than turning it into a 500")
  void upstreamErrorsArePreserved() throws Exception {
    server
        .expect(requestTo(UPSTREAM + "/v1/tweets/missing"))
        .andRespond(withStatus(HttpStatus.NOT_FOUND));

    // A downstream 404 is a fact about the resource. Reporting it as a gateway 500 sends
    // whoever is on call looking for a bug in a service that behaved correctly.
    ResponseEntity<byte[]> response = controller.proxy(request("GET", "/v1/tweets/missing"));

    assertThat(response.getStatusCode().value()).isEqualTo(404);
  }

  @Test
  @DisplayName("passes an upstream 5xx through as itself")
  void upstreamServerErrors() throws Exception {
    server
        .expect(requestTo(UPSTREAM + "/v1/tweets"))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

    assertThat(controller.proxy(request("GET", "/v1/tweets")).getStatusCode().value())
        .isEqualTo(503);
  }

  @Test
  @DisplayName("forwards the method and the body")
  void forwardsTheRequest() throws Exception {
    server
        .expect(requestTo(UPSTREAM + "/v1/tweets"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().string("{\"text\":\"hi\"}"))
        .andRespond(withStatus(HttpStatus.CREATED));

    MockHttpServletRequest request = request("POST", "/v1/tweets");
    request.setContent("{\"text\":\"hi\"}".getBytes());

    assertThat(controller.proxy(request).getStatusCode().value()).isEqualTo(201);
    server.verify();
  }

  @Test
  @DisplayName("carries the query string with the path")
  void forwardsTheQueryString() throws Exception {
    server.expect(requestTo(UPSTREAM + "/v1/tweets?limit=20&after=abc")).andRespond(withSuccess());

    MockHttpServletRequest request = request("GET", "/v1/tweets");
    request.setQueryString("limit=20&after=abc");

    controller.proxy(request);
    server.verify();
  }

  @Test
  @DisplayName("forwards the Authorization header, because the upstream checks it too")
  void forwardsAuthorization() throws Exception {
    server
        .expect(requestTo(UPSTREAM + "/v1/tweets"))
        .andExpect(header("Authorization", "Bearer token-abc"))
        .andRespond(withSuccess());

    // Each service validates independently. Stripping the token here would make the gateway
    // the only thing standing between a port-forward and the data.
    MockHttpServletRequest request = request("GET", "/v1/tweets");
    request.addHeader("Authorization", "Bearer token-abc");

    controller.proxy(request);
    server.verify();
  }

  @Test
  @DisplayName("does not forward hop-by-hop headers")
  void stripsHopByHop() throws Exception {
    server
        .expect(requestTo(UPSTREAM + "/v1/tweets"))
        .andExpect(headerDoesNotExist("Connection"))
        .andRespond(withSuccess());

    // These describe the client's connection to the gateway, which does not exist on the hop
    // from the gateway to the upstream.
    MockHttpServletRequest request = request("GET", "/v1/tweets");
    request.addHeader("Connection", "keep-alive");

    controller.proxy(request);
    server.verify();
  }

  @Test
  @DisplayName("does not forward the client's Host header")
  void stripsHost() throws Exception {
    server.expect(requestTo(UPSTREAM + "/v1/tweets")).andRespond(withSuccess());

    // The upstream's virtual host is the upstream's, not the caller's. Forwarding it makes
    // routing depend on whatever hostname the client happened to dial.
    MockHttpServletRequest request = request("GET", "/v1/tweets");
    request.addHeader("Host", "api.example.com");

    controller.proxy(request);
    server.verify();
  }

  @Test
  @DisplayName("answers 404 for a path no service owns")
  void unroutable() throws Exception {
    assertThat(controller.proxy(request("GET", "/v1/nonsense")).getStatusCode().value())
        .isEqualTo(404);
  }

  @Test
  @DisplayName("answers 504 when the upstream cannot be reached")
  void upstreamUnreachable() throws Exception {
    // Port 1 is reserved and nothing listens on it, so the connection is refused immediately.
    ProxyController unreachable =
        new ProxyController(RestClient.builder().build(), routes("http://127.0.0.1:1"));

    // 504 says the upstream was the problem. A 500 would blame the gateway for a service that
    // never ran, which is the most misleading thing an edge can report.
    assertThat(unreachable.proxy(request("GET", "/v1/tweets")).getStatusCode().value())
        .isEqualTo(504);
  }

  @Test
  @DisplayName("a request with no body is forwarded without one")
  void emptyBody() throws Exception {
    server
        .expect(requestTo(UPSTREAM + "/v1/tweets"))
        .andExpect(content().string(""))
        .andRespond(withSuccess());

    controller.proxy(request("GET", "/v1/tweets"));
    server.verify();
  }

  @Test
  @DisplayName("does not echo the upstream's transfer headers back to the client")
  void sanitisesResponseHeaders() throws Exception {
    server
        .expect(requestTo(UPSTREAM + "/v1/tweets"))
        .andRespond(
            withSuccess("body", MediaType.TEXT_PLAIN)
                .header("Transfer-Encoding", "chunked")
                .header("X-Trace", "keep-me"));

    // Content-Length and Transfer-Encoding describe the body on the connection it arrived on.
    // Echoing them onto a different connection is how a proxy truncates a response it copied
    // perfectly.
    ResponseEntity<byte[]> response = controller.proxy(request("GET", "/v1/tweets"));

    assertThat(response.getHeaders().headerNames()).doesNotContain("Transfer-Encoding");
    assertThat(response.getHeaders().getFirst("X-Trace")).isEqualTo("keep-me");
  }
}
