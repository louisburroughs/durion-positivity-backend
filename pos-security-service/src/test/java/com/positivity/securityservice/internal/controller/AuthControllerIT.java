package com.positivity.securityservice.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import com.positivity.securityservice.BaseContractIntegrationTest;
import com.positivity.securityservice.internal.dto.LoginRequest;
import com.positivity.securityservice.internal.dto.TokenPairResponse;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.service.JwtService;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

/**
 * AUTH-001 RED integration tests for {@link AuthController}.
 *
 * <h3>ADR Compliance</h3>
 * <ul>
 * <li><strong>ADR-0017</strong>: HTTP status codes — 200 success, 401 bad
 * credentials, 400 validation failure.</li>
 * <li><strong>ADR-0011 / ADR-0014</strong>: Login endpoint is permit-all;
 * no upstream gateway auth token required for this endpoint.</li>
 * </ul>
 *
 * <h3>Test-to-Acceptance Criterion mapping</h3>
 * <table>
 * <tr>
 * <th>Test</th>
 * <th>AC</th>
 * <th>RED?</th>
 * <th>Failure reason in RED phase</th>
 * </tr>
 * <tr>
 * <td>T1</td>
 * <td>AC1, AC3, AC6, AC7 (no deprecated endpoint check)</td>
 * <td>YES</td>
 * <td>Stub throws UnsupportedOperationException → HTTP 500, expects 200</td>
 * </tr>
 * <tr>
 * <td>T2</td>
 * <td>AC4</td>
 * <td>YES</td>
 * <td>Stub throws UnsupportedOperationException → HTTP 500, expects 401</td>
 * </tr>
 * <tr>
 * <td>T3</td>
 * <td>AC4</td>
 * <td>YES</td>
 * <td>Stub throws UnsupportedOperationException → HTTP 500, expects 401</td>
 * </tr>
 * <tr>
 * <td>T4</td>
 * <td>AC5</td>
 * <td>YES</td>
 * <td>pos-security-service pom.xml lacks spring-boot-starter-validation;
 * Hibernate Validator is absent so @NotBlank does not fire, stub is reached
 * and throws UnsupportedOperationException → 500. GREEN requires adding the
 * dependency AND the working implementation.</td>
 * </tr>
 * <tr>
 * <td>T5</td>
 * <td>AC5</td>
 * <td>YES</td>
 * <td>Same as T4</td>
 * </tr>
 * <tr>
 * <td>T6</td>
 * <td>AC5</td>
 * <td>NO (GREEN immediately)</td>
 * <td>HttpMessageNotReadableException fired by Jackson (no validator needed) →
 * GlobalExceptionHandler → 400; stub never reached.</td>
 * </tr>
 * </table>
 *
 * <p>
 * <strong>Note on T4/T5</strong>: These are RED because
 * {@code spring-boot-starter-validation} is not yet declared in
 * {@code pos-security-service/pom.xml}. Adding it (alongside the GREEN
 * implementation)
 * is required for Bean Validation ({@code @NotBlank}) to fire before the
 * controller method.
 *
 * <p>
 * <strong>T7 (no raw BCrypt)</strong>: Covered by
 * {@code AuthenticationServiceImplTest#login_doesNotUsePasswordEncoderDirectly}
 * as a unit test — verifying that {@code PasswordEncoder} is not a dependency
 * of
 * {@code AuthenticationServiceImpl}.
 *
 * @see AuthenticationServiceImplTest
 * @since AUTH-001
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("AuthControllerIT — AUTH-001")
class AuthControllerIT extends BaseContractIntegrationTest {

    private static final String LOGIN_PATH = "/v1/auth/login";
    private static final String TEST_USERNAME = "auth001_user";
    private static final String TEST_PASSWORD = "password";
    private static final String TEST_ROLE = "SHOP_MANAGER";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RoleRepository roleRepository;

    @Value("${security.jwt.secret}")
    private String jwtSecret;

    @BeforeEach
    void seedUser() {
        // Ensure the role exists
        if (!roleRepository.existsByName(TEST_ROLE)) {
            Role role = new Role();
            role.setName(TEST_ROLE);
            role.setDescription("AUTH-001 test role");
            role.setCreatedBy("auth-001-test");
            roleRepository.save(role);
        }

        // Remove any stale user from a previous test run
        userRepository.findByUsername(TEST_USERNAME).ifPresent(userRepository::delete);

        Role role = roleRepository.findByName(TEST_ROLE)
                .orElseThrow(() -> new IllegalStateException("Test role SHOP_MANAGER not found after save"));

        User user = new User();
        user.setUsername(TEST_USERNAME);
        user.setPassword(passwordEncoder.encode(TEST_PASSWORD));
        user.setRoles(Set.of(role));
        userRepository.save(user);
    }

    // =========================================================
    // T1 — Valid credentials → 200 + TokenPairResponse
    // RED: stub throws UnsupportedOperationException → 500
    // =========================================================

    @Test
    @DisplayName("T1 [RED]: valid credentials → HTTP 200, non-blank tokens, perm_bits claim, no authorities claim")
    void login_validCredentials_returns200WithTokenPairAndPermBits() throws Exception {
        String body = objectMapper.writeValueAsString(new LoginRequest(TEST_USERNAME, TEST_PASSWORD));

        // RED: stub throws UnsupportedOperationException → HTTP 500
        // GREEN: returns 200 with TokenPairResponse
        MvcResult result = mockMvc.perform(post(LOGIN_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk()) // FAILS in RED (500 ≠ 200)
                .andReturn();

        // Reached only in GREEN phase:
        TokenPairResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                TokenPairResponse.class);

        assertThat(response.accessToken())
                .as("accessToken must not be blank")
                .isNotBlank();
        assertThat(response.refreshToken())
                .as("refreshToken must not be blank")
                .isNotBlank();

        // Verify JWT claims: perm_bits present, authorities absent (AC6 / PERM
        // contract)
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

        assertThat(claims.get(JwtService.PERM_BITS))
                .as("accessToken must contain perm_bits claim")
                .isNotNull();
        assertThat(claims.get(JwtService.AUTHORITIES))
                .as("accessToken must NOT contain legacy authorities claim")
                .isNull();
    }

    // =========================================================
    // T2 — Wrong password → 401 INVALID_CREDENTIALS
    // RED: stub throws UnsupportedOperationException → 500
    // =========================================================

    @Test
    @DisplayName("T2 [RED]: wrong password → HTTP 401 with code INVALID_CREDENTIALS")
    void login_wrongPassword_returns401InvalidCredentials() throws Exception {
        String body = objectMapper.writeValueAsString(new LoginRequest(TEST_USERNAME, "wrongpassword"));

        // RED: stub throws UnsupportedOperationException → HTTP 500
        // GREEN: AuthenticationManager throws BadCredentialsException → 401
        mockMvc.perform(post(LOGIN_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isUnauthorized()) // FAILS in RED (500 ≠ 401)
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    // =========================================================
    // T3 — Non-existent user → 401 INVALID_CREDENTIALS (no enumeration oracle)
    // RED: stub throws UnsupportedOperationException → 500
    // =========================================================

    @Test
    @DisplayName("T3 [RED]: non-existent user → HTTP 401 with code INVALID_CREDENTIALS (no user enumeration)")
    void login_nonExistentUser_returns401InvalidCredentials() throws Exception {
        String body = objectMapper.writeValueAsString(new LoginRequest("nobody_xyz", "anything"));

        // RED: stub throws UnsupportedOperationException → HTTP 500
        // GREEN: same 401 response as wrong-password — prevents user enumeration
        mockMvc.perform(post(LOGIN_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isUnauthorized()) // FAILS in RED (500 ≠ 401)
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    // =========================================================
    // T4 — Blank username → 400
    // GREEN immediately: @NotBlank fires before the stub is reached
    // =========================================================

    @Nested
    @DisplayName("Validation tests (AC5)")
    class ValidationTests {

        @Test
        @DisplayName("T4 [RED]: blank username → HTTP 400 — RED until spring-boot-starter-validation added to pom.xml")
        void login_blankUsername_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(new LoginRequest("", "somepassword"));

            // RED: spring-boot-starter-validation absent → @NotBlank does NOT fire →
            // stub is reached → UnsupportedOperationException → 500.
            // GREEN requires: (1) add spring-boot-starter-validation to pom.xml,
            // (2) working implementation so validation fires before reaching service.
            mockMvc.perform(post(LOGIN_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("T5 [RED]: blank password → HTTP 400 — RED until spring-boot-starter-validation added to pom.xml")
        void login_blankPassword_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(new LoginRequest(TEST_USERNAME, ""));

            // RED: same root cause as T4.
            mockMvc.perform(post(LOGIN_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("T6 [GREEN immediately]: missing request body → HTTP 400")
        void login_missingBody_returns400() throws Exception {
            // Jackson throws HttpMessageNotReadableException (no Hibernate Validator
            // needed)
            // → GlobalExceptionHandler → 400; stub never reached.
            mockMvc.perform(post(LOGIN_PATH)
                    .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());
        }
    }
}
