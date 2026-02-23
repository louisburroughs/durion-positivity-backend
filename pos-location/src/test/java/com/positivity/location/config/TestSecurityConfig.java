package com.positivity.location.config;

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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@TestConfiguration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@Profile("test")
public class TestSecurityConfig {

    private static final List<SimpleGrantedAuthority> TEST_AUTHORITIES = List.of(
            // Location domain authorities
            new SimpleGrantedAuthority("location:write"),
            new SimpleGrantedAuthority("location:read"),
            // Mobile unit domain authorities
            new SimpleGrantedAuthority("location.mobile-unit.manage"),
            new SimpleGrantedAuthority("location.mobile-unit.read"),
            // Service area domain authorities (same as mobile unit)
            new SimpleGrantedAuthority("location.service-area.manage"),
            new SimpleGrantedAuthority("location.service-area.read"),
            // Travel buffer policy authorities
            new SimpleGrantedAuthority("location.travel-buffer-policy.manage"),
            new SimpleGrantedAuthority("location.travel-buffer-policy.read"),
            // Bay domain authorities
            new SimpleGrantedAuthority("location:bay:manage"),
            new SimpleGrantedAuthority("location:bay:read"));

    @Bean(name = "gatewaySecurityFilterChain")
    @Primary
    public SecurityFilterChain gatewaySecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .addFilterBefore(new TestAutoAuthFilter(), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(registry -> registry.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Primary
    public UserDetailsService testUserDetailsService() {
        var user = User.withUsername("location-test-user")
                .password("{noop}test")
                .authorities(TEST_AUTHORITIES)
                .build();
        return new InMemoryUserDetailsManager(user);
    }

    private static class TestAutoAuthFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {
            var authentication = new UsernamePasswordAuthenticationToken(
                    "location-test-user", null, TEST_AUTHORITIES);
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        }
    }
}
