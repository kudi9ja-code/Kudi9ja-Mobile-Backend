package com.quadrilateral.kudi9ja.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quadrilateral.kudi9ja.common.error.ApiError;
import com.quadrilateral.kudi9ja.common.error.ErrorCode;
import com.quadrilateral.kudi9ja.security.auth.JwtAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * What is open and what is not.
 *
 * <p>The rule is that everything requires a token unless it is listed here, so
 * a new endpoint is protected by default rather than by remembering to protect
 * it. The open list is short on purpose: signing up, signing in, the rates the
 * app displays before anyone has an account, and the legal documents, which
 * have to be readable by someone deciding whether to open an account at all.
 *
 * <p>Sessions are stateless. There is no server session and no CSRF token,
 * because there is no cookie to forge a request with: every call carries a
 * bearer token the app holds.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] OPEN_ENDPOINTS = {
            "/api/v1/auth/signup/**",
            "/api/v1/auth/signin",
            "/api/v1/auth/refresh",
            "/api/v1/auth/otp/**",
            "/api/v1/auth/password/forgot",
            "/api/v1/auth/password/reset",
            "/api/v1/settings/public",
            "/api/v1/legal/**",
            "/api/v1/banks",
            "/api/v1/states",
            // Paystack holds no token; its calls are proven by the signature
            // over the body, which PaystackPaymentService checks first.
            "/api/v1/webhooks/**",
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    private final JwtAuthenticationFilter jwtFilter;
    private final ObjectMapper objectMapper;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter, ObjectMapper objectMapper) {
        this.jwtFilter = jwtFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // A live stream (help and complaints) ends in an async dispatch that
                        // carries no token; the request that opened it was already checked.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(OPEN_ENDPOINTS).permitAll()
                        // Panel access is a database grant, resolved per request
                        // by the filter. The role here is only the first gate;
                        // each endpoint re-checks the specific permission.
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) -> write(
                                response,
                                HttpServletResponse.SC_UNAUTHORIZED,
                                ErrorCode.UNAUTHENTICATED,
                                "Sign in to continue.",
                                request.getRequestURI()))
                        .accessDeniedHandler((request, response, ex) -> write(
                                response,
                                HttpServletResponse.SC_FORBIDDEN,
                                ErrorCode.FORBIDDEN,
                                "You do not have access to that.",
                                request.getRequestURI())))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * The mobile app is not a browser origin, so CORS matters only for the
     * admin panel and the API docs. It is left narrow deliberately.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            org.springframework.core.env.Environment environment) {
        CorsConfiguration configuration = new CorsConfiguration();
        String origins = environment.getProperty("kudi9ja.cors.allowed-origins", "");
        configuration.setAllowedOriginPatterns(
                origins.isBlank() ? List.of("http://localhost:*") : List.of(origins.split(",")));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Device"));
        configuration.setExposedHeaders(List.of("Idempotency-Replayed"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private void write(HttpServletResponse response, int status, ErrorCode code, String message, String path)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(),
                ApiError.of(code, message, Map.of(), path));
    }
}
