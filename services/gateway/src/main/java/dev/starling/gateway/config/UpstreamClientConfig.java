/* SPDX-License-Identifier: MIT */
package dev.starling.gateway.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The single client every proxied request goes through.
 *
 * <p>Timeouts are the whole point of this class. The default {@code RestClient} waits forever, and
 * a gateway that waits forever on one slow service stops answering for all of them — the request
 * threads are the shared resource, so one degraded upstream becomes a site-wide outage rather than
 * a feature outage.
 *
 * <p>The builder is injected rather than created with {@code RestClient.builder()}. Only the
 * auto-configured builder carries the Micrometer observation interceptor, and without it the
 * gateway opens a span for the inbound request, calls an upstream without propagating the
 * traceparent header, and produces a trace that stops at the edge. Nothing fails; the trace is
 * simply and quietly wrong.
 *
 * <p>Redirects are not followed. A 302 from an upstream is meant for the browser, and resolving it
 * here would hide the redirect from the client while making the gateway fetch a URL it never
 * validated.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayProperties.class)
public class UpstreamClientConfig {

  @Bean
  public RestClient upstreamClient(RestClient.Builder builder, GatewayProperties properties) {
    Duration timeout = properties.upstreamTimeout();
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    factory.setReadTimeout(timeout);
    return builder.requestFactory(factory).build();
  }
}
