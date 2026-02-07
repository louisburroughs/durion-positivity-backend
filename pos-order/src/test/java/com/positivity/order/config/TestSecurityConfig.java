package com.positivity.order.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

import java.io.IOException;
import java.util.List;

/**
 * Test security configuration that replaces gateway-based authentication
 * with a permissive filter chain for integration tests.
 */
@TestConfiguration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@Profile("test")
public class TestSecurityConfig {

    /**
     * All order authorities needed by controller {@code @PreAuthorize} checks.
     */
    private static final List<SimpleGrantedAuthority> TEST_AUTHORITIES = List.of(
            new SimpleGrantedAuthority("order:price_override:view"),
            new SimpleGrantedAuthority("order:price_override:create"),
            new SimpleGrantedAuthority("order:price_override:approve"),
            new SimpleGrantedAuthority("order:price_override:reject"),
            new SimpleGrantedAuthority("order:price_override:admin"));

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
