package com.positivity.workorder.config;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
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
            new SimpleGrantedAuthority("workorder.operationalContext.override"),
            // Issue CAP-142: Story #60 — daily dispatch board dashboard view authority
            new SimpleGrantedAuthority("workorder:dashboard:view"),
            // Issue CAP-218: pick list view/execute and parts consume authorities
            new SimpleGrantedAuthority("inventory:pick_list:view"),
            new SimpleGrantedAuthority("inventory:pick_list:execute"),
            new SimpleGrantedAuthority("workorder:parts:consume"));

    /**
     * Request attribute name: when set to {@code Boolean.TRUE} on a MockMvc request,
     * the TestAutoAuthFilter injects a limited authority set that excludes pick and
     * consume permissions. Use this in 403 test cases.
     */
    public static final String NO_PICK_AUTH_ATTR = "test.no-pick-auth";

    private static final List<SimpleGrantedAuthority> NO_PICK_AUTHORITIES = List.of(
            new SimpleGrantedAuthority("workorder:workorder:view"),
            new SimpleGrantedAuthority("workorder:workorder:create"));

    private static final UUID TEST_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Bean(name = "gatewaySecurityFilterChain")
    @Primary
    @Order(0)
    public SecurityFilterChain gatewaySecurityFilterChain(HttpSecurity http) {
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
            String username = request.getHeader("X-User");
            if (username == null || username.isBlank()) {
                username = "workorder-test-user";
            }

            UUID userId = TEST_USER_ID;
            String userIdHeader = request.getHeader("X-User-Id");
            if (userIdHeader != null && !userIdHeader.isBlank()) {
                try {
                    userId = UUID.fromString(userIdHeader);
                } catch (IllegalArgumentException ignored) {
                    userId = TEST_USER_ID;
                }
            }

            List<SimpleGrantedAuthority> authorities =
                    Boolean.TRUE.equals(request.getAttribute(NO_PICK_AUTH_ATTR))
                            ? NO_PICK_AUTHORITIES
                            : TEST_AUTHORITIES;
            var authentication = new UsernamePasswordAuthenticationToken(
                    username, null, authorities);
            authentication.setDetails(Map.of(
                    "username", username,
                    "userId", userId));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        }
    }
}
