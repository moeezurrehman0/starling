/* SPDX-License-Identifier: MIT */
package dev.twitterclone.tweet.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * The S3 presigner used to hand clients a direct upload grant.
 *
 * <p>Only the presigner, not an S3 client. This service never touches an object: media bytes go
 * from the browser straight to S3 and come back through CloudFront, so the request path never
 * carries a multi-megabyte body and a pod is never sized for one.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MediaProperties.class)
public class MediaConfig {

  @Bean
  public S3Presigner s3Presigner(MediaProperties properties) {
    var builder = S3Presigner.builder();
    if (properties.endpoint() != null) {
      builder
          .endpointOverride(properties.endpoint())
          // Tier L only. LocalStack serves a single host, so the virtual-hosted form
          // -- bucket.localhost -- does not resolve; real S3 gets the modern form.
          .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
    }
    return builder.build();
  }
}
