package com.positivity.securityservice.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.securityservice.BaseContractIntegrationTest;
import com.positivity.securityservice.internal.dto.LoginRequest;
import com.positivity.securityservice.internal.dto.RefreshTokenRequest;
import com.positivity.securityservice.internal.dto.TokenPairResponse;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.RoleAssignment;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.repository.JwtTokenRepository;
import com.positivity.securityservice.internal.repository.RoleAssignmentRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.internal.service.RoleManagementService;
import java.time.Clock;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

/**
 * ADR-0061 §4 amendment (2026-09-09, #1914 phase 3): revoking a role assignment ends the holder's
 * live tokens immediately, rather than leaving a stale {@code perm_bits} valid for the rest of the
 * access token's natural lifetime.
 *
 * <p>Deliberately NOT {@code @Transactional}: {@link
 * com.positivity.securityservice.internal.service.RoleAssignmentTokenRevocationListener} reacts
 * {@code AFTER_COMMIT}, so the revoking call's transaction must actually commit for the listener
 * to fire, exactly as it does in production against a real request. {@code security.redis.enabled}
 * is {@code false} under the {@code test} profile (H2, no Docker), so this exercises the
 * fail-open-Redis / {@code jwt_token}-row-deletion path — deletion alone is what {@link
 * com.positivity.securityservice.internal.service.JwtServiceImpl#validateToken} and {@code
 * refreshAccessToken} consult, so revocation is observable here without Redis.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("RoleAssignmentRevocationIT — #1914 phase 3")
class RoleAssignmentRevocationIT extends BaseContractIntegrationTest {

    private static final String LOGIN_PATH = "/v1/auth/login";
    private static final String VALIDATE_PATH = "/v1/auth/validate";
    private static final String REFRESH_PATH = "/v1/auth/refresh";
    private static final String USERNAME = "role_revocation_it_user";
    private static final String PASSWORD = "password";
    private static final String ROLE_NAME = "REVOCATION_IT_ROLE";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private RoleAssignmentRepository roleAssignmentRepository;

    @Autowired
    private JwtTokenRepository jwtTokenRepository;

    @Autowired
    private RoleManagementService roleManagementService;

    @Autowired
    private Clock clock;

    private User user;
    private Role role;

    @BeforeEach
    void seedUser() {
        if (!roleRepository.existsByName(ROLE_NAME)) {
            Role newRole = new Role();
            newRole.setName(ROLE_NAME);
            newRole.setDescription("RoleAssignmentRevocationIT test role");
            newRole.setCreatedBy("role-revocation-it");
            roleRepository.save(newRole);
        }
        role = roleRepository
                .findByName(ROLE_NAME)
                .orElseThrow(() -> new IllegalStateException("Test role not found after save"));

        deleteUserAndAssignmentsIfPresent(USERNAME);
        User newUser = new User();
        newUser.setUsername(USERNAME);
        newUser.setPassword(passwordEncoder.encode(PASSWORD));
        user = userRepository.save(newUser);

        RoleAssignment assignment = new RoleAssignment();
        assignment.setUser(user);
        assignment.setRole(role);
        assignment.setEffectiveStartDate(LocalDateTime.now(clock));
        assignment.setCreatedBy("role-revocation-it");
        roleAssignmentRepository.saveAndFlush(assignment);
    }

    private void deleteUserAndAssignmentsIfPresent(String username) {
        userRepository.findByUsername(username).ifPresent(existing -> {
            roleAssignmentRepository.deleteAll(roleAssignmentRepository.findByUser(existing));
            jwtTokenRepository.deleteAll(jwtTokenRepository.findAllBySubject(username));
            userRepository.delete(existing);
        });
    }

    @Test
    @DisplayName("revoking the holder's only role ends their live access token: validate flips to false")
    void revokeRoleFromUser_endsLiveAccessToken() throws Exception {
        TokenPairResponse tokens = login();

        // Sanity: the token is valid before revocation.
        mockMvc.perform(get(VALIDATE_PATH).param("token", tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        // Service-level call: RoleController/UserRoleController layer on @PreAuthorize, which is
        // orthogonal to the revocation mechanism this test exercises.
        roleManagementService.revokeRoleFromUser(user.getId(), role.getId());

        mockMvc.perform(get(VALIDATE_PATH).param("token", tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
    }

    @Test
    @DisplayName("revoking the holder's only role ends their refresh token: refresh no longer succeeds")
    void revokeRoleFromUser_endsLiveRefreshToken() throws Exception {
        TokenPairResponse tokens = login();

        roleManagementService.revokeRoleFromUser(user.getId(), role.getId());

        String refreshBody = objectMapper.writeValueAsString(new RefreshTokenRequest(tokens.refreshToken()));
        MvcResult result = mockMvc.perform(post(REFRESH_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("a revoked refresh token must not yield a new token pair")
                .isNotEqualTo(200);
    }

    @Test
    @DisplayName("revocation deletes the jwt_token row (the store validateToken and refresh consult)")
    void revokeRoleFromUser_deletesStoredTokenRow() throws Exception {
        TokenPairResponse tokens = login();
        assertThat(jwtTokenRepository.findByToken(tokens.accessToken())).isPresent();

        roleManagementService.revokeRoleFromUser(user.getId(), role.getId());

        assertThat(jwtTokenRepository.findByToken(tokens.accessToken())).isEmpty();
        assertThat(jwtTokenRepository.findByRefreshToken(tokens.refreshToken())).isEmpty();
    }

    @Test
    @DisplayName("RoleController's revokeRoleAssignment (by assignment id) also ends the holder's live token")
    void revokeRoleAssignmentById_endsLiveAccessToken() throws Exception {
        TokenPairResponse tokens = login();
        RoleAssignment assignment =
                roleAssignmentRepository.findByUser(user).stream().findFirst().orElseThrow();

        roleManagementService.revokeRoleAssignment(assignment.getId(), LocalDateTime.now(clock));

        mockMvc.perform(get(VALIDATE_PATH).param("token", tokens.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
    }

    private TokenPairResponse login() throws Exception {
        String body = objectMapper.writeValueAsString(new LoginRequest(USERNAME, PASSWORD));
        MvcResult result = mockMvc.perform(
                        post(LOGIN_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), TokenPairResponse.class);
    }
}
