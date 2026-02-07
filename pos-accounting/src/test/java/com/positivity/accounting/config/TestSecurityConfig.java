package com.positivity.accounting.config;

import java.io.IOException;
import java.util.List;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import com.positivity.accounting.internal.config.AccountingSecurityConfig;
import com.positivity.security.common.GatewaySecurityConfig;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Test security configuration that replaces gateway-based authentication
 * with a permissive filter chain for integration tests.
 *
 * <p>
 * Replaces the production {@link GatewaySecurityConfig} (imported via
 * {@link AccountingSecurityConfig}) to avoid conflicting "any request"
 * filter chains (Spring Security 6.2+ rejects duplicate catch-all chains).
 * A filter auto-populates the {@link SecurityContextHolder} with a test
 * user so that {@code @PreAuthorize} checks pass.
 * </p>
 */
@TestConfiguration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@Profile("test")
public class TestSecurityConfig {

    /**
     * All accounting authorities needed by controller {@code @PreAuthorize} checks.
     */
    private static final List<SimpleGrantedAuthority> TEST_AUTHORITIES = List.of(
            new SimpleGrantedAuthority("accounting:je:view"),
            new SimpleGrantedAuthority("accounting:je:create"),
            new SimpleGrantedAuthority("accounting:je:post"),
            new SimpleGrantedAuthority("accounting:je:reverse"),
            new SimpleGrantedAuthority("accounting:coa:view"),
            new SimpleGrantedAuthority("accounting:coa:create"),
            new SimpleGrantedAuthority("accounting:coa:edit"),
            new SimpleGrantedAuthority("accounting:coa:deactivate"),
            new SimpleGrantedAuthority("accounting:events:view"),
            new SimpleGrantedAuthority("accounting:events:submit"),
            new SimpleGrantedAuthority("accounting:events:retry"),
            new SimpleGrantedAuthority("accounting:posting_rules:view"),
            new SimpleGrantedAuthority("accounting:posting_rules:create"),
            new SimpleGrantedAuthority("accounting:posting_rules:publish"),
            new SimpleGrantedAuthority("accounting:posting_rules:archive"),
            new SimpleGrantedAuthority("accounting:ap:view"),
            new SimpleGrantedAuthority("accounting:ap:pay"),
            new SimpleGrantedAuthority("accounting:mappings:view"),
            new SimpleGrantedAuthority("accounting:mappings:create"),
            new SimpleGrantedAuthority("accounting:audit:view"));

    /**
     * Replaces the production gateway filter chain with a permissive one that
     * auto-authenticates every request with the test user.
     */
    @Bean(name = "gatewaySecurityFilterChain")
    @Primary
    public SecurityFilterChain gatewaySecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .addFilterBefore(new TestAutoAuthFilter(), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Primary
    public UserDetailsService testUserDetailsService() {
        var user = User.withUsername("testuser")
                .password("{noop}test")
                .authorities(TEST_AUTHORITIES)
                .build();
        return new InMemoryUserDetailsManager(user);
    }

    /**
     * Filter that populates the SecurityContext with a fully-authorised test
     * principal on every request, so {@code @PreAuthorize} method-level
     * checks pass without requiring real authentication.
     */
    private static class TestAutoAuthFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request,
                HttpServletResponse response, FilterChain filterChain)
                throws ServletException, IOException {
            var authentication = new UsernamePasswordAuthenticationToken(
                    "testuser", null, TEST_AUTHORITIES);
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        }
    }
}
