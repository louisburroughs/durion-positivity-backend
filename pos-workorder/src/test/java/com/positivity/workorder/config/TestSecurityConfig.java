package com.positivity.workorder.config;

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

/**
 * Test security configuration that replaces the gateway filter chain with a
 * permissive chain for integration tests.
 */
@TestConfiguration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@Profile("test")
public class TestSecurityConfig {

    private static final List<SimpleGrantedAuthority> TEST_AUTHORITIES = List.of(
            new SimpleGrantedAuthority("workorder:approval_config:view"),
            new SimpleGrantedAuthority("workorder:approval_config:create"),
            new SimpleGrantedAuthority("workorder:approval_config:edit"),
            new SimpleGrantedAuthority("workorder:approval_config:delete"),
            new SimpleGrantedAuthority("workorder:estimate:view"),
            new SimpleGrantedAuthority("workorder:estimate:create"),
            new SimpleGrantedAuthority("workorder:estimate:edit"),
            new SimpleGrantedAuthority("workorder:estimate:delete"),
            new SimpleGrantedAuthority("workorder:estimate:decline"),
            new SimpleGrantedAuthority("workorder:estimate:reopen"),
            new SimpleGrantedAuthority("workorder:estimate:approve"),
            new SimpleGrantedAuthority("workorder:estimate:promote"),
            new SimpleGrantedAuthority("workorder:estimate:submit"),
            new SimpleGrantedAuthority("workorder:estimate:calculate"),
            new SimpleGrantedAuthority("workorder:estimate_item:add"),
            new SimpleGrantedAuthority("workorder:estimate_item:edit"),
            new SimpleGrantedAuthority("workorder:estimate_item:delete"),
            new SimpleGrantedAuthority("workorder:estimate_item:view"),
            new SimpleGrantedAuthority("workorder:estimate_snapshot:create"),
            new SimpleGrantedAuthority("workorder:estimate_snapshot:view"),
            new SimpleGrantedAuthority("workorder:change_request:create"),
            new SimpleGrantedAuthority("workorder:change_request:approve"),
            new SimpleGrantedAuthority("workorder:change_request:decline"),
            new SimpleGrantedAuthority("workorder:change_request:emergency_override"),
            new SimpleGrantedAuthority("workorder:change_request:view"),
            new SimpleGrantedAuthority("workorder:workorder:view"),
            new SimpleGrantedAuthority("workorder:workorder:create"),
            new SimpleGrantedAuthority("workorder:workorder:edit"),
            new SimpleGrantedAuthority("workorder:workorder:delete"),
            new SimpleGrantedAuthority("workorder:workorder:approve"),
            new SimpleGrantedAuthority("workorder:workorder:start"),
            new SimpleGrantedAuthority("workorder:workorder:complete"),
            new SimpleGrantedAuthority("workorder:workorder:generate_invoice"),
            new SimpleGrantedAuthority("workorder:workorder:reopen_completed"),
            new SimpleGrantedAuthority("workorder:workorder:assign-technician"),
            new SimpleGrantedAuthority("workorder:invoice:view"),
            new SimpleGrantedAuthority("workorder:invoice:create"),
            new SimpleGrantedAuthority("workorder:parts:view"),
            new SimpleGrantedAuthority("workorder:parts:add"),
            new SimpleGrantedAuthority("workorder:labor:view"),
            new SimpleGrantedAuthority("workorder:labor:add"),
            new SimpleGrantedAuthority("workorder:start"),
            new SimpleGrantedAuthority("workorder:operationalContext:override"),
            // Issue CAP-140: Story #59 — operational context override authority
            new SimpleGrantedAuthority("workorder.operationalContext.override"));

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
        var user = User.withUsername("workorder-test-user")
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
                    "workorder-test-user", null, TEST_AUTHORITIES);
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        }
    }
}
