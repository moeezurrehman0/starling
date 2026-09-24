/* SPDX-License-Identifier: MIT */
package dev.starling.user.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * The signing key for access tokens.
 *
 * <p>RS256 rather than HS256, so that the gateway and every other service can verify a token with a
 * public key they are free to hold. A shared secret would mean any service able to check a token is
 * also able to mint one, which makes a single compromised service enough to impersonate every user.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

  private static final Logger LOG = LoggerFactory.getLogger(JwtConfig.class);

  @Bean
  public RSAKey signingKey(JwtProperties properties) {
    return properties.configuredSigningKey().map(JwtConfig::parse).orElseGet(JwtConfig::ephemeral);
  }

  @Bean
  public JWKSource<SecurityContext> jwkSource(RSAKey signingKey) {
    return new ImmutableJWKSet<>(new JWKSet(signingKey));
  }

  @Bean
  public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
    return new NimbusJwtEncoder(jwkSource);
  }

  /**
   * Verifies tokens this service issued.
   *
   * <p>The service validates its own bearer tokens rather than trusting the gateway to have done
   * it. The gateway is a routing rule; a service that is only safe because of one stops being safe
   * the moment something reaches it another way -- a port-forward, a sidecar, a second Ingress --
   * and nothing about that failure looks like a failure.
   */
  @Bean
  public JwtDecoder jwtDecoder(RSAKey signingKey, JwtProperties properties) {
    try {
      NimbusJwtDecoder decoder =
          NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey()).build();
      decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
      return decoder;
    } catch (JOSEException e) {
      throw new IllegalStateException("Signing key has no usable public half", e);
    }
  }

  private static RSAKey parse(String pem) {
    try {
      return RSAKey.parseFromPEMEncodedObjects(pem).toRSAKey();
    } catch (Exception e) {
      // Deliberately fatal. Falling back to a generated key here would turn a
      // misconfigured secret into a service that starts happily and issues tokens
      // nothing else can verify.
      throw new IllegalStateException("starling.jwt.signing-key is not a usable RSA PEM", e);
    }
  }

  /**
   * A key generated at startup, for Tier L only.
   *
   * <p>Every restart invalidates every outstanding token and two replicas would disagree about
   * which signatures are valid, so this is a local-development convenience and nothing more. It is
   * loud on purpose: an environment that reaches this line without meaning to has no working
   * authentication and the log is the only place that will say so.
   */
  private static RSAKey ephemeral() {
    LOG.warn(
        "No starling.jwt.signing-key configured; generating an ephemeral RSA key. "
            + "Tokens will not survive a restart and will not verify across replicas. "
            + "This is only ever correct for local development.");
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      KeyPair pair = generator.generateKeyPair();
      return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
          .privateKey((RSAPrivateKey) pair.getPrivate())
          .keyID(UUID.randomUUID().toString())
          .build();
    } catch (Exception e) {
      throw new IllegalStateException("Could not generate an ephemeral RSA key", e);
    }
  }
}
