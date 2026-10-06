package com.almahwar.api.security;

import com.almahwar.api.config.ApiProperties;
import com.almahwar.api.error.ErrorResponseWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.List;

/**
 * HTTP security of the API.
 * <ul>
 *   <li>Stateless: no HTTP session, no cookies; every request carries {@code Authorization: Bearer <token>}.
 *       CSRF protection is therefore not needed (no ambient credentials a browser could replay) and is off.</li>
 *   <li>Public: liveness / readiness, and the login. Everything else needs a valid token — deny by default.</li>
 *   <li>Authorization is permission-based ({@code PERM_*}) and enforced in the service layer with
 *       {@code @PreAuthorize}, close to the data — never in the client.</li>
 *   <li>OpenAPI / Swagger UI paths are public only when springdoc is enabled (the {@code dev} profile).</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfig {

    static final String[] PUBLIC_GET = {"/api/v1/health", "/api/v1/health/ready"};
    static final String LOGIN = "/api/v1/auth/login";
    static final String[] DOCS = {"/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**"};

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, UserPrincipalLoader principalLoader,
                                    SecurityErrorHandlers errors, ErrorResponseWriter writer,
                                    @Value("${springdoc.api-docs.enabled:false}") boolean docsEnabled) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())   // the "corsConfigurationSource" bean below
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .anonymous(a -> { })
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(HttpMethod.GET, PUBLIC_GET).permitAll();
                    auth.requestMatchers(HttpMethod.POST, LOGIN).permitAll();
                    auth.requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll();   // CORS pre-flight only
                    auth.requestMatchers("/error").permitAll();
                    if (docsEnabled) {
                        auth.requestMatchers(DOCS).permitAll();
                    }
                    auth.anyRequest().authenticated();
                })
                .oauth2ResourceServer(o -> o
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(principalLoader))
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors))
                .exceptionHandling(e -> e.authenticationEntryPoint(errors).accessDeniedHandler(errors))
                .addFilterAfter(new PasswordChangeRequiredFilter(writer), BearerTokenAuthenticationFilter.class)
                .headers(h -> h.contentSecurityPolicy(csp -> csp.policyDirectives(
                        docsEnabled ? "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'"
                                : "default-src 'none'; frame-ancestors 'none'")));
        return http.build();
    }

    /**
     * Browser cross-origin access: only the configured origins (Flutter Web / tools), never {@code *}. Native mobile
     * apps are not subject to CORS. No cookies are used, so credentials are not allowed.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(ApiProperties properties) {
        List<String> origins = properties.cors().allowedOrigins().stream().map(String::trim)
                .filter(o -> !o.isEmpty()).toList();
        for (String origin : origins) {
            if (origin.contains("*")) {
                throw new IllegalStateException("almahwar.api.cors.allowed-origins must list exact origins, not '*'");
            }
        }
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (!origins.isEmpty()) {
            CorsConfiguration cfg = new CorsConfiguration();
            cfg.setAllowedOrigins(origins);
            cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
            cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Request-Id"));
            cfg.setExposedHeaders(List.of("X-Request-Id", "Retry-After"));
            cfg.setAllowCredentials(false);
            cfg.setMaxAge(Duration.ofHours(1));
            source.registerCorsConfiguration("/api/**", cfg);
        }
        return source;
    }
}
