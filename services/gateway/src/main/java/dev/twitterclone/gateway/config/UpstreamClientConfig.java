/* SPDX-License-Identifier: MIT */
package dev.twitterclone.gateway.config;

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
 * <p>Redirects are not followed. A 302 from an upstream is meant for the browser, and resolving it
 * here would hide the redirect from the client while making the gateway fetch a URL it never
 * validated.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayProperties.class)
public class UpstreamClientConfig {

  @Bean
  public RestClient upstreamClient(GatewayProperties properties) {
    Duration timeout = properties.upstreamTimeout();
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    factory.setReadTimeout(timeout);
    return RestClient.builder().requestFactory(factory).build();
  }
}
