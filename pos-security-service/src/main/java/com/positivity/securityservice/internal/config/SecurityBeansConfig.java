package com.positivity.securityservice.internal.config;

import com.positivity.securityservice.internal.security.JwtAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class SecurityBeansConfig {
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Keeps {@link JwtAuthenticationFilter} on the {@code /v1/auth/**} security chain only, where
     * {@code SecurityConfig} places it.
     *
     * <p>Any {@code Filter} bean is also registered with the servlet container by Spring Boot, at
     * {@code /*} and — having no order of its own — at {@code Ordered.LOWEST_PRECEDENCE}, which is
     * <em>after</em> {@code springSecurityFilterChain} (order {@code -100}). On the chains this
     * filter is not part of ({@code /v1/users/**}, {@code /v1/roles/**}, {@code /v1/permissions/**},
     * the actuator chain), {@code OncePerRequestFilter}'s already-filtered guard does not suppress
     * that container copy, because the filter never ran in-chain there. It therefore executed after
     * authorization had already passed on gateway headers — and, since #1715 made it clear the
     * security context for a refused bearer token, it would strip the authentication those
     * endpoints had already been authorized on: a method-secured endpoint would answer 403 instead
     * of the 401 the filter intends, and one without method security would run with a null
     * principal, so {@code GlobalExceptionHandler}'s actor lookup and JPA auditing would record the
     * wrong actor.
     *
     * <p>Disabling the container registration leaves exactly one invocation, inside the chain that
     * declares it. Nothing depended on the container copy: on those other chains the authorization
     * filter runs first, so a request the copy would have authenticated was already rejected before
     * it ran.
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
