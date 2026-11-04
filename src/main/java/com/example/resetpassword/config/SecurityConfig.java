package com.example.resetpassword.config;

import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

/** 002 research R1: one stateless chain; 001 routes and public auth endpoints are open, /me and /password need a JWT. */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    static final String UNAUTHENTICATED_BODY =
            "{\"type\":\"/problems/unauthenticated\",\"title\":\"Authentication required.\",\"status\":401}";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        AuthenticationEntryPoint entryPoint = unauthenticatedEntryPoint();
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                .requestCache(c -> c.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/forgot-password", "/reset-password", "/api/password-reset/**").permitAll()
                        .requestMatchers("/api/auth/register", "/api/auth/sign-in", "/api/auth/refresh",
                                "/api/auth/sign-out", "/complete-registration").permitAll()
                        .requestMatchers("/api/auth/me", "/api/auth/password").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()).authenticationEntryPoint(entryPoint))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint));
        return http.build();
    }

    /** Identical response for every cause (missing, expired, tampered, stale version, disabled). */
    static AuthenticationEntryPoint unauthenticatedEntryPoint() {
        return (request, response, ex) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setHeader("WWW-Authenticate", "Bearer");
            response.setContentType("application/problem+json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(UNAUTHENTICATED_BODY);
        };
    }
}
