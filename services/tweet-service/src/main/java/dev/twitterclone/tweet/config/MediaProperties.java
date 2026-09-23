/* SPDX-License-Identifier: MIT */
package dev.twitterclone.tweet.config;

import java.net.URI;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where tweet media lives and how long an upload grant lasts.
 *
 * @param bucket the S3 bucket holding uploaded images
 * @param endpoint override for the S3 endpoint, set to LocalStack in Tier L only
 * @param uploadTtl how long a presigned PUT stays valid
 * @param maxBytes largest upload the presigned grant will admit
 */
@ConfigurationProperties("twitterclone.media")
public record MediaProperties(
    String bucket, @Nullable URI endpoint, Duration uploadTtl, long maxBytes) {

  public MediaProperties {
    bucket = bucket == null || bucket.isBlank() ? "twitterclone-media" : bucket;
    // Long enough for a phone on a bad connection to finish, short enough that a grant
    // leaked in a log or a shared screenshot is worthless by the time anyone finds it.
    uploadTtl = uploadTtl == null ? Duration.ofMinutes(10) : uploadTtl;
    // Enforced by the signature itself, not by the service. A limit the client is merely
    // asked to respect is not a limit.
    maxBytes = maxBytes <= 0 ? 5L * 1024 * 1024 : maxBytes;
  }
}
