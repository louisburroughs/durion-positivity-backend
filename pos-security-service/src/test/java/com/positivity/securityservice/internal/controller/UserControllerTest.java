package com.positivity.securityservice.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.securityservice.internal.dto.UserDto;
import com.positivity.securityservice.internal.exception.DuplicateUsernameException;
import com.positivity.securityservice.internal.security.JwtAuthenticationFilter;
import com.positivity.securityservice.internal.service.CustomUserDetailsService;
import com.positivity.securityservice.internal.service.UserService;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller slice tests for {@link UserController#createUser}: validated request body
 * (the former untyped map 500'd on malformed payloads), 409 on duplicate usernames,
 * and security:user:create enforcement. All service calls are mocked.
 */
@WebMvcTest(UserController.class)
@Import(UserControllerTest.SliceTestConfig.class)
@NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.INHERIT)
@DisplayName("UserControllerTest — createUser")
class UserControllerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-08-26T12:00:00Z"), ZoneOffset.UTC);
    private static final String VALID_BODY = """
            {"username":"jane.doe","password":"Sup3rS3cret!","roles":["TECHNICIAN"]}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @BeforeEach
    void configureJwtFilterPassthrough() throws Exception {
        doAnswer(inv -> {
                    ((FilterChain) inv.getArgument(2)).doFilter(inv.getArgument(0), inv.getArgument(1));
                    return null;
                })
                .when(jwtAuthenticationFilter)
                .doFilter(any(), any(), any());
    }

    @Nested
    @DisplayName("POST /v1/users")
    class CreateUser {

        @Test
        void withAuthority_returns201WithUser() throws Exception {
            when(userService.createUser(eq("jane.doe"), eq("Sup3rS3cret!"), anySet()))
                    .thenReturn(UserDto.builder()
                            .id(UUID.fromString("01990000-0000-7000-8000-000000000001"))
                            .username("jane.doe")
                            .roles(Set.of("TECHNICIAN"))
                            .build());

            mockMvc.perform(post("/v1/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_BODY)
                            .with(user("admin-user").authorities(() -> "security:user:create")))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.username").value("jane.doe"));
        }

        @Test
        void missingPassword_returns400() throws Exception {
            mockMvc.perform(post("/v1/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"jane.doe","roles":["TECHNICIAN"]}""")
                            .with(user("admin-user").authorities(() -> "security:user:create")))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void emptyRoles_returns400() throws Exception {
            mockMvc.perform(post("/v1/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"username":"jane.doe","password":"Sup3rS3cret!","roles":[]}""")
                            .with(user("admin-user").authorities(() -> "security:user:create")))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void duplicateUsername_returns409() throws Exception {
            when(userService.createUser(any(), any(), anySet()))
                    .thenThrow(new DuplicateUsernameException("Username already exists"));

            mockMvc.perform(post("/v1/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_BODY)
                            .with(user("admin-user").authorities(() -> "security:user:create")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("USER_ALREADY_EXISTS"));
        }

        @Test
        void withoutAuthority_returns403() throws Exception {
            mockMvc.perform(post("/v1/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_BODY)
                            .with(user("viewer-user").roles("VIEWER")))
                    .andExpect(status().isForbidden());
        }

        @Test
        void unauthenticated_returns401() throws Exception {
            mockMvc.perform(post("/v1/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_BODY))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("PUT /v1/users/{id}/person-link")
    class LinkUserPerson {

        private static final String LINK_PATH = "/v1/users/01990000-0000-7000-8000-000000000001/person-link";
        private static final String LINK_BODY = """
                {"personId":"01960011-0000-7000-8000-000000000001"}""";

        @Test
        void withAuthority_returns202() throws Exception {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(LINK_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(LINK_BODY)
                            .with(user("admin-user").authorities(() -> "security:user:edit")))
                    .andExpect(status().isAccepted());

            org.mockito.Mockito.verify(userService)
                    .requestPersonLink(
                            UUID.fromString("01990000-0000-7000-8000-000000000001"),
                            UUID.fromString("01960011-0000-7000-8000-000000000001"));
        }

        @Test
        void unknownUser_returns404() throws Exception {
            org.mockito.Mockito.doThrow(new com.positivity.securityservice.internal.exception.UserNotFoundException(
                            "User not found"))
                    .when(userService)
                    .requestPersonLink(any(), any());

            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(LINK_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(LINK_BODY)
                            .with(user("admin-user").authorities(() -> "security:user:edit")))
                    .andExpect(status().isNotFound());
        }

        @Test
        void missingPersonId_returns400() throws Exception {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(LINK_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")
                            .with(user("admin-user").authorities(() -> "security:user:edit")))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void withoutAuthority_returns403() throws Exception {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(LINK_PATH)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(LINK_BODY)
                            .with(user("viewer-user").roles("VIEWER")))
                    .andExpect(status().isForbidden());
        }
    }

    @TestConfiguration
    @EnableWebSecurity
    @EnableMethodSecurity(prePostEnabled = true)
    static class SliceTestConfig {

        @Bean
        Clock clock() {
            return TEST_CLOCK;
        }

        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .httpBasic(Customizer.withDefaults());
            return http.build();
        }
    }
}
