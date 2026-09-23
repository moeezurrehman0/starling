/* SPDX-License-Identifier: MIT */
package dev.twitterclone.user.config;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/** Who may call what, and how passwords are hashed. */
@Configuration(proxyBeanMethods = false)
// Explicit rather than left to autoconfiguration. It is what contributes the HttpSecurity
// builder the filterChain bean below takes as a parameter, and relying on a starter to have
// contributed it makes this class fail to construct in any context that loads it directly --
// a @WebMvcTest slice being the one that matters, since that is where the matcher ordering
// gets tested.
@EnableWebSecurity
public class SecurityConfig {

  /**
   * Bcrypt at cost 12.
   *
   * <p>Cost is the only defence once a dump leaks, and 12 is roughly a quarter-second on the
   * hardware this runs on -- unnoticeable on a login, ruinous across a stolen table. The value is
   * encoded in every stored hash, so raising it later re-hashes accounts as they log in rather than
   * requiring a migration.
   */
  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http
        // No cookies and no sessions anywhere in this system, so there is no ambient
        // authority for a cross-site request to borrow and nothing for CSRF to protect.
        // Leaving it on would reject every POST from a bearer-token client.
        .csrf(csrf -> csrf.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    // The ERROR dispatch above is permitted, and that is a correctness fix
                    // rather than a convenience. When a handler throws, the container
                    // re-dispatches to /error, Spring Security evaluates that dispatch too,
                    // and anyRequest().authenticated() rejects it -- so an unhandled 500
                    // inside a *public* endpoint reaches the caller as 401. The real fault
                    // becomes invisible and the reported one is a lie; it cost an afternoon
                    // once. Matching on the dispatch type rather than permitting the "/error"
                    // path means a client still cannot request /error directly.
                    .requestMatchers("/actuator/health/**", "/actuator/info")
                    .permitAll()
                    // Prometheus scrapes in-cluster and the endpoint is not exposed
                    // through the gateway; a NetworkPolicy is what actually restricts
                    // it, and that is Phase 6's job, recorded in the gap register.
                    .requestMatchers("/actuator/prometheus")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/v1/users", "/v1/sessions")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/v1/jwks")
                    .permitAll()
                    // Ordered before the wildcard below, and the order is the whole
                    // point: matchers are evaluated top to bottom and the first hit
                    // wins, so "/v1/users/**".permitAll() placed first would make
                    // /v1/users/me anonymous -- which is not a 401 but a null
                    // principal and a 500, an outage rather than a refusal.
                    .requestMatchers(HttpMethod.GET, "/v1/users/me", "/v1/users/*/followers/me")
                    .authenticated()
                    // Profiles are public on Twitter and public here. Making them
                    // authenticated would mean the logged-out landing page cannot
                    // render, which is a product decision disguised as a security one.
                    .requestMatchers(HttpMethod.GET, "/v1/users/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
        .build();
  }
}
