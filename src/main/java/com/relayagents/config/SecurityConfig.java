package com.relayagents.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayagents.shared.web.ActorArgumentResolver;
import com.relayagents.shared.web.ProblemHandler;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SecurityConfig {

    private static final String READ = "SCOPE_relay.read";
    private static final String WRITE = "SCOPE_relay.write";
    private static final String APPROVE = "SCOPE_relay.approve";

    @Bean
    SecurityFilterChain api(HttpSecurity http, ObjectMapper json) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                // Webhooks authenticate with an HMAC signature per trigger, checked in the controller.
                .requestMatchers(HttpMethod.POST, "/v1/hooks/*").permitAll()
                .requestMatchers(HttpMethod.POST, "/v1/runs/*/approvals/*").hasAuthority(APPROVE)
                .requestMatchers(HttpMethod.GET, "/v1/**").hasAuthority(READ)
                .requestMatchers(HttpMethod.POST, "/v1/**").hasAuthority(WRITE)
                .requestMatchers(HttpMethod.PUT, "/v1/**").hasAuthority(WRITE)
                .anyRequest().denyAll())
            .oauth2ResourceServer(oauth -> oauth
                .jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationConverter()))
                .authenticationEntryPoint((request, response, e) ->
                    writeProblem(response, json, HttpStatus.UNAUTHORIZED, "unauthenticated", "A valid bearer token is required"))
                .accessDeniedHandler((request, response, e) ->
                    writeProblem(response, json, HttpStatus.FORBIDDEN, "forbidden", "Token lacks the required scope")))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, e) ->
                    writeProblem(response, json, HttpStatus.UNAUTHORIZED, "unauthenticated", "A valid bearer token is required"))
                .accessDeniedHandler((request, response, e) ->
                    writeProblem(response, json, HttpStatus.FORBIDDEN, "forbidden", "Token lacks the required scope")));
        return http.build();
    }

    /**
     * Every query is scoped by tenant, so a token without one is rejected here rather than half-working.
     */
    static JwtAuthenticationConverter authenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        JwtAuthenticationConverter scopesOnly = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String tenant = jwt.getClaimAsString(ActorArgumentResolver.TENANT_CLAIM);
            if (tenant == null || !tenant.matches("[a-z0-9][a-z0-9-]{0,62}")) {
                throw new InvalidBearerTokenException("Token has no valid tenant claim");
            }
            return scopesOnly.convert(jwt).getAuthorities();
        });
        return converter;
    }

    private static void writeProblem(HttpServletResponse response, ObjectMapper json, HttpStatus status, String code,
                                     String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), ProblemHandler.problem(status, code, detail));
    }
}
