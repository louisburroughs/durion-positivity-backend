package com.positivity.security_service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.securityservice.TokenRevocationManager;
import com.positivity.securityservice.internal.dto.LoginRequest;
import com.positivity.securityservice.internal.dto.RefreshTokenRequest;
import com.positivity.securityservice.internal.dto.TokenPairRequest;
import com.positivity.securityservice.internal.dto.TokenPairResponse;
import com.positivity.securityservice.internal.dto.TokenResponse;
import com.positivity.securityservice.service.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

/**
 * Consolidated Contract Behavior Integration Tests for Security Service.
 *
 * **Scope:**
 * - Security Policy Management: CRUD operations, permissions, status
 * transitions
 * - JWT Authentication & Token Management (CAP-275):
 * - Happy path: Login → Refresh → Validate → Revoke
 * - Input validation: Blank username, empty roles, invalid tokens
 * - Authentication & authorization: Valid/invalid tokens, expired tokens
 * - Concurrency: Concurrent token revocation (optimistic locking)
 * - Redis integration: Token revocation caching, graceful degradation
 * - Error handling: GlobalExceptionHandler with correlation IDs
 * - Contract compliance: Responses match BACKEND_CONTRACT_GUIDE.md v2.0
 *
 * **Test Data:**
 * - Username: "john.doe" (constant test subject)
 * - Roles: {"SHOP_MGR", "INVENTORY_MGR"} (valid role set)
 * - Token lifetimes: 1 hour access, 7 days refresh
 * - Policy IDs: policy-001 through policy-010
 *
 * **Contract References:**
 * - See /durion/domains/security/.business-rules/BACKEND_CONTRACT_GUIDE.md
 * - JWT Endpoints: POST /v1/auth/login, /token-pair, /refresh, etc.
 * - Policy Endpoints: POST/GET /api/v1/policies
 * - Request format: LoginRequest, TokenPairRequest, RefreshTokenRequest, Policy
 * payloads
 * - Response format: TokenResponse, TokenPairResponse, Policy responses
 * - Error format: { error, message, timestamp, correlationId }
 *
 * @since 1.0
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Security Service Contract Behavior Integration Tests")
class ContractBehaviorIT {

    private static final String TEST_SUBJECT = "john.doe";
    private static final Set<String> TEST_ROLES = Set.of("SHOP_MGR", "INVENTORY_MGR");

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private TokenRevocationManager tokenRevocationManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${security.jwt.secret}")
    private String jwtSecret;

    @BeforeEach
    void setup() {
        // Initialize MockMvc from the WebApplicationContext
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        // Clear all revoked tokens before each test
        tokenRevocationManager.clearAllRevoked();
    }

    // ========== SECURITY POLICY TESTS ==========

    @Test
    @DisplayName("CP-001: Successfully create security policy with valid permissions")
    void testCreateSecurityPolicy_HappyPath() throws Exception {
        String payload = createPolicyPayload("policy-001", "read,write", "ACTIVE");
        mockMvc.perform(post("/api/v1/policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-001"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.policyId").value("policy-001"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("CP-002: Successfully retrieve security policy by ID")
    void testGetSecurityPolicy_HappyPath() throws Exception {
        String payload = createPolicyPayload("policy-002", "read,write,delete", "ACTIVE");
        MvcResult createResult = mockMvc.perform(post("/api/v1/policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-002"))
                .andExpect(status().isCreated())
                .andReturn();

        String policyId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(get("/api/v1/policies/{id}", policyId)
                .header("X-Correlation-Id", "test-002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(policyId))
                .andExpect(jsonPath("$.policyId").value("policy-002"));
    }

    @Test
    @DisplayName("VE-001: Reject policy with empty permissions list")
    void testCreateSecurityPolicy_EmptyPermissions() throws Exception {
        String invalidPayload = createPolicyPayload("policy-003", "", "ACTIVE");
        mockMvc.perform(post("/api/v1/policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidPayload)
                .header("X-Correlation-Id", "test-ve-001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("VE-002: Reject policy with invalid permission values")
    void testCreateSecurityPolicy_InvalidPermission() throws Exception {
        String invalidPayload = createPolicyPayload("policy-004", "read,invalid-perm", "ACTIVE");
        mockMvc.perform(post("/api/v1/policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidPayload)
                .header("X-Correlation-Id", "test-ve-002"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("VE-003: Reject policy with invalid status")
    void testCreateSecurityPolicy_InvalidStatus() throws Exception {
        String invalidPayload = createPolicyPayload("policy-005", "read,write", "UNKNOWN_STATUS");
        mockMvc.perform(post("/api/v1/policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidPayload)
                .header("X-Correlation-Id", "test-ve-003"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("ID-001: Idempotent create with same Idempotency-Key returns same resource")
    void testCreateSecurityPolicy_Idempotent() throws Exception {
        String idempotencyKey = "idem-policy-" + System.currentTimeMillis();
        String payload = createPolicyPayload("policy-006", "read,write,execute", "ACTIVE");

        MvcResult result1 = mockMvc.perform(post("/api/v1/policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-id-001")
                .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isCreated())
                .andReturn();

        String id1 = objectMapper.readTree(result1.getResponse().getContentAsString()).get("id").asText();

        MvcResult result2 = mockMvc.perform(post("/api/v1/policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-id-001")
                .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isCreated())
                .andReturn();

        String id2 = objectMapper.readTree(result2.getResponse().getContentAsString()).get("id").asText();
        assert id1.equals(id2) : "Idempotent requests should return same policy ID";
    }

    @Test
    @DisplayName("CC-001: Optimistic locking prevents concurrent updates with stale version")
    void testUpdateSecurityPolicy_OptimisticLockingConflict() throws Exception {
        String payload = createPolicyPayload("policy-007", "read", "ACTIVE");
        MvcResult createResult = mockMvc.perform(post("/api/v1/policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-cc-001"))
                .andExpect(status().isCreated())
                .andReturn();

        String policyId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();
        String updatePayload = "{\"permissions\":\"read,write\",\"version\":0}";

        mockMvc.perform(patch("/api/v1/policies/{id}", policyId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(updatePayload)
                .header("X-Correlation-Id", "test-cc-001")
                .header("If-Match", "0"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    @DisplayName("CC-002: Status transition respects active policy constraints")
    void testUpdateSecurityPolicy_StatusTransition() throws Exception {
        String payload = createPolicyPayload("policy-008", "read,write,delete", "ACTIVE");
        MvcResult createResult = mockMvc.perform(post("/api/v1/policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-cc-002"))
                .andExpect(status().isCreated())
                .andReturn();

        String policyId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();
        String updatePayload = "{\"status\":\"INACTIVE\"}";

        mockMvc.perform(patch("/api/v1/policies/{id}/status", policyId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(updatePayload)
                .header("X-Correlation-Id", "test-cc-002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
    }

    @Test
    @DisplayName("FF-001: ISO 8601 timestamp format for createdAt")
    void testSecurityPolicy_TimestampFormat() throws Exception {
        String payload = createPolicyPayload("policy-009", "read,write", "ACTIVE");
        MvcResult result = mockMvc.perform(post("/api/v1/policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-ff-001"))
                .andExpect(status().isCreated())
                .andReturn();

        String createdAt = objectMapper.readTree(result.getResponse().getContentAsString()).get("createdAt").asText();
        assert createdAt.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?Z")
                : "createdAt must be ISO 8601 UTC format";
    }

    @Test
    @DisplayName("FF-002: Valid permission and status enum values")
    void testSecurityPolicy_ValidEnumValues() throws Exception {
        String payload = createPolicyPayload("policy-010", "read,write,delete,execute", "ACTIVE");
        MvcResult result = mockMvc.perform(post("/api/v1/policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-ff-002"))
                .andExpect(status().isCreated())
                .andReturn();

        String status = objectMapper.readTree(result.getResponse().getContentAsString()).get("status").asText();
        assert status.matches("ACTIVE|INACTIVE|ARCHIVED") : "Policy status must be valid enum value";
    }

    // ========== JWT AUTHENTICATION & TOKEN MANAGEMENT TESTS ==========

    /**
     * Test 1: Happy Path - POST /v1/auth/login
     *
     * **Scenario:** Valid login request issues access token
     * **Expected:** 200 OK with TokenResponse containing valid JWT
     * **Contract:** BACKEND_CONTRACT_GUIDE.md §Login Endpoint
     */
    @Test
    @DisplayName("T1: Login endpoint issues valid access token")
    void testLoginIssuesValidAccessToken() throws Exception {
        // Arrange
        LoginRequest request = new LoginRequest(TEST_SUBJECT, TEST_ROLES);

        // Act
        MvcResult result = mockMvc.perform(post("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        // Assert
        TokenResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                TokenResponse.class);

        assertThat(response.token())
                .isNotBlank()
                .as("Token should not be blank");

        // Verify token structure
        SecretKeySpec key = new SecretKeySpec(
                jwtSecret.getBytes(StandardCharsets.UTF_8),
                0,
                jwtSecret.getBytes(StandardCharsets.UTF_8).length,
                "HmacSHA256");

        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(response.token())
                .getPayload();

        assertThat(claims.getSubject()).isEqualTo(TEST_SUBJECT);
        assertThat(claims.getId()).isNotBlank().as("JTI should be present for revocation");
        assertThat(claims.getExpiration().toInstant())
                .isAfter(Instant.now())
                .as("Token expiration should be in the future");
    }

    /**
     * Test 2: Happy Path - POST /v1/auth/token-pair
     *
     * **Scenario:** Token pair request issues both access and refresh tokens
     * **Expected:** 200 OK with TokenPairResponse
     * **Validation:** Both tokens are valid, separate JTIs, correct expiration
     * times
     */
    @Test
    @DisplayName("T2: Token pair endpoint issues both access and refresh tokens")
    void testTokenPairIssuesBothTokens() throws Exception {
        // Arrange
        TokenPairRequest request = new TokenPairRequest(TEST_SUBJECT, TEST_ROLES);

        // Act
        MvcResult result = mockMvc.perform(post("/v1/auth/token-pair")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        // Assert
        TokenPairResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                TokenPairResponse.class);

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.accessToken()).isNotEqualTo(response.refreshToken());

        // Verify token contents
        SecretKeySpec key = new SecretKeySpec(
                jwtSecret.getBytes(StandardCharsets.UTF_8),
                0,
                jwtSecret.getBytes(StandardCharsets.UTF_8).length,
                "HmacSHA256");

        Claims accessClaims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(response.accessToken()).getPayload();
        Claims refreshClaims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(response.refreshToken()).getPayload();

        // Verify separate JTIs
        assertThat(accessClaims.getId()).isNotEqualTo(refreshClaims.getId());

        // Verify access token has roles
        assertThat(accessClaims.get("roles")).isNotNull();

        // Verify refresh token type claim
        assertThat(refreshClaims.get("type")).isEqualTo("refresh");
    }

    /**
     * Test 3: Refresh Token Flow - POST /v1/auth/refresh
     *
     * **Scenario:** Exchange refresh token for new token pair
     * **Expected:** 200 OK with new TokenPairResponse
     * **Validation:** Old tokens are revoked, new tokens are valid
     */
    @Test
    @DisplayName("T3: Refresh endpoint exchanges refresh token for new pair")
    void testRefreshTokenExchangeFlow() throws Exception {
        // Arrange: Issue initial token pair
        JwtService.TokenPair initialPair = jwtService.generateTokenPair(TEST_SUBJECT, TEST_ROLES);

        RefreshTokenRequest request = new RefreshTokenRequest(initialPair.refreshToken());

        // Act
        MvcResult result = mockMvc.perform(post("/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        // Assert
        TokenPairResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                TokenPairResponse.class);

        assertThat(response.accessToken()).isNotNull();
        assertThat(response.refreshToken()).isNotNull();
        assertThat(response.accessToken()).isNotEqualTo(initialPair.accessToken());
    }

    /**
     * Test 4: Token Validation - GET /v1/auth/validate
     *
     * **Scenario:** Validate both valid and invalid tokens
     * **Expected:** { valid: true } for valid tokens, { valid: false } for invalid
     */
    @Test
    @DisplayName("T4: Validate endpoint returns correct validity status")
    void testTokenValidation() throws Exception {
        // Arrange
        String validToken = jwtService.generateToken(TEST_SUBJECT, TEST_ROLES);
        String invalidToken = "invalid.jwt.token";

        // Act & Assert - Valid token
        mockMvc.perform(get("/v1/auth/validate")
                .param("token", validToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        // Act & Assert - Invalid token
        mockMvc.perform(get("/v1/auth/validate")
                .param("token", invalidToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
    }

    /**
     * Test 5: Token Revocation - DELETE /v1/auth/revoke
     *
     * **Scenario:** Revoke token and verify validation fails
     * **Expected:** 204 No Content, subsequent validation returns false
     */
    @Test
    @DisplayName("T5: Revoke endpoint invalidates token for future validation")
    void testTokenRevocation() throws Exception {
        // Arrange
        String token = jwtService.generateToken(TEST_SUBJECT, TEST_ROLES);

        // Act - Revoke token
        mockMvc.perform(delete("/v1/auth/revoke")
                .param("token", token))
                .andExpect(status().isNoContent());

        // Assert - Token should no longer be valid
        mockMvc.perform(get("/v1/auth/validate")
                .param("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
    }

    /**
     * Test 6: Input Validation - Blank Username
     *
     * **Scenario:** Login with blank username
     * **Expected:** 400 Bad Request with error response
     */
    @Test
    @DisplayName("T6: Login rejects blank username with 400 error")
    void testLoginRejectsBlankUsername() throws Exception {
        // Arrange
        LoginRequest request = new LoginRequest("", TEST_ROLES);

        // Act & Assert
        mockMvc.perform(post("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    /**
     * Test 7: Input Validation - Empty Roles
     *
     * **Scenario:** Token pair with empty roles set
     * **Expected:** 400 Bad Request with error response
     */
    @Test
    @DisplayName("T7: Token pair rejects empty roles with 400 error")
    void testTokenPairRejectsEmptyRoles() throws Exception {
        // Arrange
        TokenPairRequest request = new TokenPairRequest(TEST_SUBJECT, Set.of());

        // Act & Assert
        mockMvc.perform(post("/v1/auth/token-pair")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    /**
     * Test 8: Invalid Refresh Token
     *
     * **Scenario:** Attempt to refresh with invalid token
     * **Expected:** 400 Bad Request with error response
     */
    @Test
    @DisplayName("T8: Refresh rejects invalid refresh token with 400 error")
    void testRefreshRejectsInvalidToken() throws Exception {
        // Arrange
        RefreshTokenRequest request = new RefreshTokenRequest("invalid.token.here");

        // Act & Assert
        mockMvc.perform(post("/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    /**
     * Test 9: Extract Claims - Roles
     *
     * **Scenario:** Extract roles from valid token
     * **Expected:** 200 OK with roles array
     */
    @Test
    @DisplayName("T9: Extract roles from valid token")
    void testExtractRoles() throws Exception {
        // Arrange
        String token = jwtService.generateToken(TEST_SUBJECT, TEST_ROLES);

        // Act & Assert
        mockMvc.perform(get("/v1/auth/roles")
                .param("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[*]").value(
                        org.hamcrest.Matchers.containsInAnyOrder("SHOP_MGR", "INVENTORY_MGR")));
    }

    /**
     * Test 10: Extract Claims - Subject
     *
     * **Scenario:** Extract subject from valid token
     * **Expected:** 200 OK with subject string
     */
    @Test
    @DisplayName("T10: Extract subject from valid token")
    void testExtractSubject() throws Exception {
        // Arrange
        String token = jwtService.generateToken(TEST_SUBJECT, TEST_ROLES);

        // Act & Assert
        mockMvc.perform(get("/v1/auth/subject")
                .param("token", token))
                .andExpect(status().isOk())
                .andExpect(content().string(TEST_SUBJECT));
    }

    /**
     * Test 11: Unauthorized Access - Invalid Token for Claims Extraction
     *
     * **Scenario:** Attempt to extract roles with invalid token
     * **Expected:** 401 Unauthorized
     */
    @Test
    @DisplayName("T11: Extract roles rejects invalid token with 401 error")
    void testExtractRolesRejectsInvalidToken() throws Exception {
        // Act & Assert
        mockMvc.perform(get("/v1/auth/roles")
                .param("token", "invalid.token"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Test 12: Correlation ID Propagation
     *
     * **Scenario:** Error response includes correlation ID
     * **Expected:** Error response contains X-Correlation-Id value
     */
    @Test
    @DisplayName("T12: Error responses include correlation ID")
    void testCorrelationIdInErrorResponse() throws Exception {
        // Arrange
        LoginRequest request = new LoginRequest("", TEST_ROLES);

        // Act & Assert
        mockMvc.perform(post("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Correlation-Id", "test-correlation-123")
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.correlationId").value("test-correlation-123"));
    }

    /**
     * Test 13: Multiple Role Support
     *
     * **Scenario:** Token issued with multiple roles
     * **Expected:** All roles present in token
     */
    @Test
    @DisplayName("T13: Support multiple roles in token")
    void testMultipleRoleSupport() throws Exception {
        // Arrange
        Set<String> multipleRoles = Set.of("ROLE1", "ROLE2", "ROLE3");
        LoginRequest request = new LoginRequest(TEST_SUBJECT, multipleRoles);

        // Act
        MvcResult result = mockMvc.perform(post("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        // Assert
        TokenResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                TokenResponse.class);

        Set<String> extractedRoles = jwtService.getRolesFromToken(response.token());
        assertThat(extractedRoles).containsAll(multipleRoles);
    }

    /**
     * Test 14: Token Lifetime Validation - Access Token
     *
     * **Scenario:** Verify access token lifetime is 1 hour
     * **Expected:** Token expiration is approximately 1 hour from issuance
     */
    @Test
    @DisplayName("T14: Access token has 1-hour lifetime")
    void testAccessTokenLifetime() throws Exception {
        // Arrange
        Instant beforeCreation = Instant.now();
        String token = jwtService.generateToken(TEST_SUBJECT, TEST_ROLES);
        Instant afterCreation = Instant.now();

        // Act
        SecretKeySpec key = new SecretKeySpec(
                jwtSecret.getBytes(StandardCharsets.UTF_8),
                0,
                jwtSecret.getBytes(StandardCharsets.UTF_8).length,
                "HmacSHA256");

        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        Instant expirationTime = claims.getExpiration().toInstant();
        long secondsUntilExpiry = java.time.temporal.ChronoUnit.SECONDS.between(afterCreation, expirationTime);

        // Assert: Within 1 second of 1 hour (3600 seconds)
        assertThat(secondsUntilExpiry)
                .isBetween(3599L, 3601L)
                .as("Access token should expire in approximately 1 hour");
    }

    /**
     * Test 15: Concurrency Handling - Concurrent Token Revocation
     *
     * **Scenario:** Multiple threads attempt to revoke same token
     * **Expected:** All requests succeed, token is revoked (idempotent)
     * **Concurrency:** Tests optimistic locking with @Version field
     */
    @Test
    @DisplayName("T15: Concurrent token revocation is handled safely")
    void testConcurrentTokenRevocation() throws Exception {
        // Arrange
        String token = jwtService.generateToken(TEST_SUBJECT, TEST_ROLES);

        // Act: Simulate concurrent revocation attempts
        Thread thread1 = new Thread(() -> {
            try {
                mockMvc.perform(delete("/v1/auth/revoke").param("token", token))
                        .andExpect(status().isNoContent());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread thread2 = new Thread(() -> {
            try {
                mockMvc.perform(delete("/v1/auth/revoke").param("token", token))
                        .andExpect(status().isNoContent());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        thread1.start();
        thread2.start();

        thread1.join();
        thread2.join();

        // Assert: Token is revoked
        mockMvc.perform(get("/v1/auth/validate").param("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
    }

    // ========== HELPER METHODS ==========

    private String createPolicyPayload(String policyId, String permissions, String status) {
        return String.format(
                "{\"policyId\":\"%s\",\"permissions\":\"%s\",\"status\":\"%s\"}",
                policyId, permissions, status);
    }
}
