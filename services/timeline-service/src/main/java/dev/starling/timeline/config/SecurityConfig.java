/* SPDX-License-Identifier: MIT */
package dev.starling.timeline.config;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * A pure resource server, validating against user-service's JWKS.
 *
 * <p>Unlike tweet-service there is no anonymous read here. A home timeline is by definition the
 * caller's own, so a request without a principal has no meaning to answer — there is no such thing
 * as the logged-out user's timeline.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable())
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
                    .requestMatchers(
                        "/actuator/health/**", "/actuator/info", "/actuator/prometheus")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
    return http.build();
  }
}
