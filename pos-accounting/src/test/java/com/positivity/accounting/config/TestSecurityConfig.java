package com.positivity.accounting.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Test security configuration that disables authentication for integration
 * tests.
 */
@TestConfiguration
@Profile("test")
public class TestSecurityConfig {

    @Bean(name = "testSecurityFilterChain")
    @Primary
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Primary
    public UserDetailsService testUserDetailsService() {
        // Create a test user with all required permissions
        User.UserBuilder builder = User.withUsername("testuser");
        var user = builder
                .password("{noop}test")
                .authorities(
                        new SimpleGrantedAuthority("accounting:je:view"),
                        new SimpleGrantedAuthority("accounting:je:create"),
                        new SimpleGrantedAuthority("accounting:je:post"),
                        new SimpleGrantedAuthority("accounting:je:reverse"),
                        new SimpleGrantedAuthority("accounting:coa:view"),
                        new SimpleGrantedAuthority("accounting:coa:create"),
                        new SimpleGrantedAuthority("accounting:coa:edit"),
                        new SimpleGrantedAuthority("accounting:events:view"),
                        new SimpleGrantedAuthority("accounting:events:submit"),
                        new SimpleGrantedAuthority("accounting:events:retry"),
                        new SimpleGrantedAuthority("accounting:posting_rules:view"),
                        new SimpleGrantedAuthority("accounting:posting_rules:create"),
                        new SimpleGrantedAuthority("accounting:posting_rules:publish"),
                        new SimpleGrantedAuthority("accounting:posting_rules:archive"),
                        new SimpleGrantedAuthority("accounting:ap:view"),
                        new SimpleGrantedAuthority("accounting:ap:pay"))
                .build();
        return new InMemoryUserDetailsManager(user);
    }
}
