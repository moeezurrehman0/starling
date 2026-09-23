/* SPDX-License-Identifier: MIT */
package dev.twitterclone.timeline.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Outbound HTTP to the two services this one reads from.
 *
 * <p>Timeouts are short and explicit. The default is no read timeout at all, which means a stalled
 * dependency does not fail a timeline render — it holds the request open indefinitely, and under
 * load that exhausts the caller's connections rather than returning an error anyone can see. A
 * timeline that is missing its celebrity tweets is a degraded answer; a timeline that never returns
 * is an outage.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TimelineProperties.class)
public class RestClientConfig {

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

  @Bean
  @Qualifier("userServiceClient")
  public RestClient userServiceClient(TimelineProperties properties) {
    return client(properties.userServiceUrl());
  }

  @Bean
  @Qualifier("tweetServiceClient")
  public RestClient tweetServiceClient(TimelineProperties properties) {
    return client(properties.tweetServiceUrl());
  }

  private static RestClient client(String baseUrl) {
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
    factory.setReadTimeout(READ_TIMEOUT);
    return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
  }
}
