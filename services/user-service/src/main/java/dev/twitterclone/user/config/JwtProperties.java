/* SPDX-License-Identifier: MIT */
package dev.twitterclone.user.config;

import java.time.Duration;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Access-token settings.
 *
 * @param issuer the {@code iss} claim, and what verifiers are configured to require
 * @param ttl how long an issued token is accepted for
 * @param signingKey a PKCS#8 RSA private key in PEM form, or null to generate one at startup
 */
@ConfigurationProperties("twitterclone.jwt")
public record JwtProperties(String issuer, Duration ttl, @Nullable String signingKey) {

  public JwtProperties {
    issuer = issuer == null ? "https://twitterclone.dev" : issuer;
    // Short, because nothing revokes a JWT. A logged-out or deleted account stays usable for
    // exactly this long, so the number is the blast radius of a stolen token, not a tuning knob.
    ttl = ttl == null ? Duration.ofMinutes(15) : ttl;
    signingKey = signingKey == null || signingKey.isBlank() ? null : signingKey;
  }

  /**
   * The configured key, if there is one.
   *
   * @return the PEM, or empty when one should be generated
   */
  public Optional<String> configuredSigningKey() {
    return Optional.ofNullable(signingKey);
  }
}
