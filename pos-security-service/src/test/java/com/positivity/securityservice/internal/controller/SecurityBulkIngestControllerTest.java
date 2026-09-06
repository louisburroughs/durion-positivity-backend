package com.positivity.securityservice.internal.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.securityservice.internal.dto.UserDto;
import com.positivity.securityservice.internal.exception.DuplicateUsernameException;
import com.positivity.securityservice.internal.exception.SecurityValidationException;
import com.positivity.securityservice.internal.security.JwtAuthenticationFilter;
import com.positivity.securityservice.internal.service.CustomUserDetailsService;
import com.positivity.securityservice.internal.service.UserService;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * The two security-service ingest endpoints. The load-bearing claim in the first is negative: no
 * password reaches the service, and none is created by a path that could log or return one.
 */
@WebMvcTest({UserBulkIngestController.class, UserPersonLinkBulkIngestController.class})
@Import(SecurityBulkIngestControllerTest.SliceTestConfig.class)
@NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.INHERIT)
@DisplayName("SecurityBulkIngestControllerTest")
@SuppressWarnings({"java:S100", "java:S1192"})
class SecurityBulkIngestControllerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-08-26T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID USER_ID = UUID.fromString("01990000-0000-7000-8000-000000000001");
    private static final UUID PERSON_ID = UUID.fromString("01990000-0000-7000-8000-000000000002");

    private static final String USERS_BODY = """
            {"jobId":"01990000-0000-7000-8000-0000000000a0",
             "locationId":"01990000-0000-7000-8000-0000000000a1",
             "operatorId":"seed-operator",
             "records":[{"username":"jane.doe","roles":["TECHNICIAN"]}]}""";

    private static final String LINKS_BODY = """
            {"jobId":"01990000-0000-7000-8000-0000000000a0",
             "locationId":"01990000-0000-7000-8000-0000000000a1",
             "operatorId":"seed-operator",
             "records":[{"username":"jane.doe","personId":"01990000-0000-7000-8000-000000000002"}]}""";

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

    private static UserDto account(UUID id, String username) {
        return UserDto.builder()
                .id(id)
                .username(username)
                .roles(Set.of("TECHNICIAN"))
                .build();
    }

    @Test
    void users_provisionWithAGeneratedPassword_neverOneFromTheRequest() throws Exception {
        when(userService.createUserWithGeneratedPassword(eq("jane.doe"), anySet()))
                .thenReturn(account(USER_ID, "jane.doe"));

        mockMvc.perform(post("/v1/users/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(USERS_BODY)
                        .with(user("admin-user").authorities(() -> "security:user:create")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.results[0].entityId").value(USER_ID.toString()));

        // The password-taking overload must never be reached from this path: a bulk file is stored,
        // so a password travelling through it would exist at rest for as long as the upload does.
        verify(userService, never()).createUser(anyString(), anyString(), anySet());
    }

    @Test
    void users_aPasswordInTheRequestIsIgnored_notHonoured() throws Exception {
        // Someone will eventually add a password column out of habit; it must not become a login.
        when(userService.createUserWithGeneratedPassword(eq("jane.doe"), anySet()))
                .thenReturn(account(USER_ID, "jane.doe"));

        String withPassword = """
                {"jobId":"01990000-0000-7000-8000-0000000000a0",
                 "locationId":"01990000-0000-7000-8000-0000000000a1",
                 "records":[{"username":"jane.doe","roles":["TECHNICIAN"],"password":"hunter2"}]}""";

        mockMvc.perform(post("/v1/users/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withPassword)
                        .with(user("admin-user").authorities(() -> "security:user:create")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1));

        verify(userService, never()).createUser(anyString(), anyString(), anySet());
    }

    @Test
    void users_anExistingUsernameIsAlreadyProvisioned_notAFailure() throws Exception {
        when(userService.createUserWithGeneratedPassword(anyString(), anySet()))
                .thenThrow(new DuplicateUsernameException("Username already exists"));

        mockMvc.perform(post("/v1/users/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(USERS_BODY)
                        .with(user("admin-user").authorities(() -> "security:user:create")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(0));
    }

    @Test
    void users_anUnknownRoleFailsItsRow() throws Exception {
        // The type UserServiceImpl actually raises for an unknown role, not a stand-in: a
        // rejection has to be recognisable as one for its message to reach the caller (#1718).
        when(userService.createUserWithGeneratedPassword(anyString(), anySet()))
                .thenThrow(new SecurityValidationException("Role not found: NOPE"));

        mockMvc.perform(post("/v1/users/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(USERS_BODY)
                        .with(user("admin-user").authorities(() -> "security:user:create")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[0].errorCode").value("USER_INGEST_FAILED"))
                .andExpect(jsonPath("$.results[0].errorMessage").value("Role not found: NOPE"));
    }

    /**
     * Issue #1718: a row lost to a server-side fault must not carry the exception's text into the
     * 200 body that reports it. The caller gets a generic code and their own correlation id.
     */
    @Test
    void users_serverFault_reportsGenericFailureAndTheCorrelationId() throws Exception {
        when(userService.createUserWithGeneratedPassword(anyString(), anySet()))
                .thenThrow(new IllegalStateException("could not execute statement [insert into sec_user ...]"));

        mockMvc.perform(post("/v1/users/bulk-ingest")
                        .header("X-Correlation-Id", "corr-from-caller")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(USERS_BODY)
                        .with(user("admin-user").authorities(() -> "security:user:create")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[0].errorCode").value("INGEST_INTERNAL_ERROR"))
                .andExpect(jsonPath("$.results[0].errorMessage", containsString("corr-from-caller")))
                .andExpect(jsonPath("$.results[0].errorMessage", not(containsString("sec_user"))));
    }

    @Test
    void users_withoutTheCreateAuthority_isForbidden() throws Exception {
        mockMvc.perform(post("/v1/users/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(USERS_BODY)
                        .with(user("nobody").authorities(() -> "security:user:view")))
                .andExpect(status().isForbidden());
    }

    @Test
    void links_resolveTheUsernameAndQueueTheLink() throws Exception {
        when(userService.getAllUsers()).thenReturn(List.of(account(USER_ID, "jane.doe")));

        mockMvc.perform(post("/v1/users/person-link/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LINKS_BODY)
                        .with(user("admin-user").authorities(() -> "security:user:edit")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1));

        verify(userService).requestPersonLink(USER_ID, PERSON_ID);
    }

    @Test
    void links_anUnknownUsernameFailsItsOwnRow() throws Exception {
        when(userService.getAllUsers()).thenReturn(List.of());

        mockMvc.perform(post("/v1/users/person-link/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LINKS_BODY)
                        .with(user("admin-user").authorities(() -> "security:user:edit")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[0].errorCode").value("USER_PERSON_LINK_USER_UNKNOWN"));

        verify(userService, never()).requestPersonLink(any(), any());
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
