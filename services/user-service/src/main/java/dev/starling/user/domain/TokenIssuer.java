/* SPDX-License-Identifier: MIT */
package dev.starling.user.domain;

import dev.starling.contracts.UserItem;
import dev.starling.user.config.JwtProperties;
import java.time.Instant;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Mints the access tokens every other service trusts.
 *
 * <p>This is the only place in the system that issues one, which is the point: every service
 * verifies, exactly one signs.
 */
@Component
public class TokenIssuer {

  private final JwtEncoder encoder;
  private final JwtProperties properties;

  public TokenIssuer(JwtEncoder encoder, JwtProperties properties) {
    this.encoder = encoder;
    this.properties = properties;
  }

  /**
   * Issues a token for an authenticated user.
   *
   * @param user the account that just proved who it is
   * @return the compact serialised JWT
   */
  public Token issue(UserItem user) {
    Instant now = Instant.now();
    Instant expiry = now.plus(properties.ttl());
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(properties.issuer())
            .issuedAt(now)
            .expiresAt(expiry)
            // The subject is the immutable user id, never the handle. Handles are
            // renameable, so a token carrying one would keep authorising the old name
            // after a rename and would authorise the wrong account once the name is
            // taken by somebody else.
            .subject(user.userId())
            // Carried so the gateway and timeline-service can render and route without a
            // lookup on every request. Both are snapshots: a rename or a threshold
            // crossing is not visible until the token expires, which is what the short
            // TTL buys.
            .claim("handle", user.handle())
            .claim("celebrity", user.celebrity())
            .build();
    Jwt jwt =
        encoder.encode(
            JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims));
    return new Token(jwt.getTokenValue(), properties.ttl().toSeconds());
  }

  /** An issued token and how long it lasts. */
  public record Token(String value, long expiresInSeconds) {}
}
