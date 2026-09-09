package com.positivity.securityservice.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.securityservice.BaseContractIntegrationTest;
import com.positivity.securityservice.internal.dto.LoginRequest;
import com.positivity.securityservice.internal.dto.TokenPairResponse;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.RoleAssignment;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.repository.RoleAssignmentRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

/**
 * ADR-0061 §4 amendment (2026-09-09, #1914 phase 3): a login-issued access token's {@code exp} is
 * clamped to the earliest end of a currently effective role assignment, exactly like the existing
 * location-reach clamp (ADR-0061 §4, #1873) it sits alongside. Uses the autowired {@link Clock}
 * rather than {@code Instant.now()} for every "now" the fixture and assertions compute against —
 * PR #1915 explains why: the application resolves "now" through the injected {@code Clock}
 * ({@code TimeConfig} supplies {@code Clock.systemUTC()} in the {@code test} profile), and a
 * fixture built against a different wall-clock read races the assertion.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("RoleAssignmentExpiryClampIT — #1914 phase 3")
class RoleAssignmentExpiryClampIT extends BaseContractIntegrationTest {

    private static final String LOGIN_PATH = "/v1/auth/login";
    private static final String USERNAME = "role_clamp_it_user";
    private static final String PASSWORD = "password";
    private static final String ROLE_NAME = "CLAMP_IT_ROLE";
    private static final Duration ASSIGNMENT_TTL = Duration.ofMinutes(10);
    private static final Duration ASSERTION_TOLERANCE = Duration.ofSeconds(5);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private RoleAssignmentRepository roleAssignmentRepository;

    @Autowired
    private Clock clock;

    @Value("${security.jwt.secret}")
    private String jwtSecret;

    @BeforeEach
    void seedUser() {
        if (!roleRepository.existsByName(ROLE_NAME)) {
            Role newRole = new Role();
            newRole.setName(ROLE_NAME);
            newRole.setDescription("RoleAssignmentExpiryClampIT test role");
            newRole.setCreatedBy("role-clamp-it");
            roleRepository.save(newRole);
        }
        Role role = roleRepository
                .findByName(ROLE_NAME)
                .orElseThrow(() -> new IllegalStateException("Test role not found after save"));

        deleteUserAndAssignmentsIfPresent(USERNAME);
        User user = new User();
        user.setUsername(USERNAME);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user = userRepository.save(user);

        // Bounded: ends ASSIGNMENT_TTL from now, well inside the 3600s natural access-token
        // lifetime, so the clamp — not the natural lifetime — must be what exp reflects.
        RoleAssignment assignment = new RoleAssignment();
        assignment.setUser(user);
        assignment.setRole(role);
        assignment.setEffectiveStartDate(LocalDateTime.now(clock).minusDays(1));
        assignment.setEffectiveEndDate(LocalDateTime.now(clock).plus(ASSIGNMENT_TTL));
        assignment.setCreatedBy("role-clamp-it");
        roleAssignmentRepository.saveAndFlush(assignment);
    }

    private void deleteUserAndAssignmentsIfPresent(String username) {
        userRepository.findByUsername(username).ifPresent(existing -> {
            roleAssignmentRepository.deleteAll(roleAssignmentRepository.findByUser(existing));
            userRepository.delete(existing);
        });
    }

    @Test
    @DisplayName("login with a bounded assignment ending in 10 minutes: exp is clamped to that end, not 3600s")
    void login_boundedAssignment_expClampedToAssignmentEnd() throws Exception {
        Instant beforeLogin = Instant.now(clock);

        String body = objectMapper.writeValueAsString(new LoginRequest(USERNAME, PASSWORD));
        MvcResult result = mockMvc.perform(
                        post(LOGIN_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn();
        TokenPairResponse response =
                objectMapper.readValue(result.getResponse().getContentAsString(), TokenPairResponse.class);

        SecretKeySpec key = new SecretKeySpec(
                jwtSecret.getBytes(StandardCharsets.UTF_8),
                0,
                jwtSecret.getBytes(StandardCharsets.UTF_8).length,
                "HmacSHA256");
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(response.accessToken())
                .getPayload();

        Instant exp = claims.getExpiration().toInstant();
        Instant expectedExp = beforeLogin.plus(ASSIGNMENT_TTL);

        assertThat(exp)
                .as("exp must be clamped to ~now+10min (the assignment's end), not now+3600s")
                .isCloseTo(expectedExp, org.assertj.core.api.Assertions.within(ASSERTION_TOLERANCE));
        assertThat(exp)
                .as("exp must be well short of the natural 3600s access-token lifetime")
                .isBefore(beforeLogin.plusSeconds(3600));
    }
}
