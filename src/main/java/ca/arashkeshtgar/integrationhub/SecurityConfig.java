package ca.arashkeshtgar.integrationhub;

import ca.arashkeshtgar.integrationhub.admin.AdminAccessFilter;
import ca.arashkeshtgar.integrationhub.admin.AdminProperties;
import ca.arashkeshtgar.integrationhub.relay.TokenValidator;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Health, info and the relay routes are open to Spring Security. The relay checks the sender's
 * token itself (see {@code TokenValidator}) so that a refusal is written to the message log like
 * any other call. The message search and the metrics need the {@code HUB_ADMIN} authority, which
 * only {@link AdminAccessFilter} grants. Everything else answers 401.
 */
@Configuration
class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, AdminProperties admin, TokenValidator tokens) throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .addFilterBefore(new AdminAccessFilter(admin, tokens), AuthorizationFilter.class)
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(EndpointRequest.to(HealthEndpoint.class, InfoEndpoint.class))
                    .permitAll()
                    .requestMatchers("/route/**", "/error")
                    .permitAll()
                    .requestMatchers("/api/messages", "/api/messages/**", "/actuator/prometheus")
                    .hasAuthority(AdminAccessFilter.AUTHORITY)
                    .anyRequest()
                    .authenticated())
        .httpBasic(Customizer.withDefaults())
        .build();
  }
}
