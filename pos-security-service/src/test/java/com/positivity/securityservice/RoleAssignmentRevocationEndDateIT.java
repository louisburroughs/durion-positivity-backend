package com.positivity.securityservice;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.RoleAssignment;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.enums.ScopeType;
import com.positivity.securityservice.internal.repository.RoleAssignmentRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;

/**
 * Integration tests for role assignment revocation endpoint with LocalDateTime
 * endDate parameter.
 * Tests verify proper handling of ISO DATE_TIME format and default behavior
 * when parameter is omitted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = PosSecurityServiceApplication.class)
@ActiveProfiles("test")
@DisplayName("Role Assignment Revocation EndDate Parameter Tests")
@Transactional
public class RoleAssignmentRevocationEndDateIT extends BaseIntegrationTest {
    private static final Clock TEST_CLOCK = Clock.systemUTC();

    private static final String TEST_CORRELATION_ID = "test-correlation-id-revocation";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private RoleAssignmentRepository roleAssignmentRepository;

    private User testUser;
    private Role testRole;
    private RoleAssignment testAssignment;

    @BeforeEach
    void setupTestData() {
        // Create test user
        testUser = new User();
        testUser.setUsername("revoke-test-user");
        testUser.setPassword("password");
        testUser = userRepository.save(testUser);

        // Create test role
        testRole = new Role();
        testRole.setName("REVOKE_TEST_ROLE");
        testRole.setDescription("Test role for revocation tests");
        testRole.setCreatedBy(TEST_USER);
        testRole = roleRepository.save(testRole);

        // Create test role assignment
        testAssignment = new RoleAssignment();
        testAssignment.setUser(testUser);
        testAssignment.setRole(testRole);
        testAssignment.setScopeType(ScopeType.GLOBAL);
        testAssignment.setEffectiveStartDate(LocalDateTime.now(TEST_CLOCK).minusDays(1));
        testAssignment.setCreatedBy(TEST_USER);
        testAssignment = roleAssignmentRepository.save(testAssignment);
    }

    @Test
    @DisplayName("Should revoke role assignment with default endDate (current date and time)")
    void testRevokeWithoutEndDate() throws Exception {
        // When: Revoking without endDate parameter
        mockMvc.perform(withAuth(delete("/v1/roles/assignments/{assignmentId}", testAssignment.getId()))
                .header("X-Correlation-Id", TEST_CORRELATION_ID))
                .andExpect(status().isNoContent());

        // Then: Assignment should have effectiveEndDate set to approximately now
        RoleAssignment revoked = roleAssignmentRepository.findById(testAssignment.getId()).orElseThrow();
        LocalDateTime now = LocalDateTime.now(TEST_CLOCK);

        // Verify endDate is set and is within last minute (allowing for test execution
        // time)
        assertThat(revoked.getEffectiveEndDate()).isNotNull();
        assertThat(revoked.getEffectiveEndDate()).isAfter(now.minusMinutes(1));
        assertThat(revoked.getEffectiveEndDate()).isBefore(now.plusMinutes(1));
    }

    @Test
    @DisplayName("Should revoke role assignment with specific endDate in ISO DATE_TIME format")
    void testRevokeWithSpecificEndDate() throws Exception {
        // Given: A specific end date and time
        LocalDateTime specificEndDate = LocalDateTime.of(2026, 2, 20, 14, 30, 0);
        String endDateParam = specificEndDate.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        // When: Revoking with endDate parameter
        mockMvc.perform(withAuth(delete("/v1/roles/assignments/{assignmentId}", testAssignment.getId()))
                .param("endDate", endDateParam)
                .header("X-Correlation-Id", TEST_CORRELATION_ID))
                .andExpect(status().isNoContent());

        // Then: Assignment should have the specified effectiveEndDate
        RoleAssignment revoked = roleAssignmentRepository.findById(testAssignment.getId()).orElseThrow();
        assertThat(revoked.getEffectiveEndDate()).isEqualTo(specificEndDate);
    }

    @Test
    @DisplayName("Should return 400 for invalid endDate format")
    void testRevokeWithInvalidEndDateFormat() throws Exception {
        // Given: An invalid date format (DATE only, not DATE_TIME)
        String invalidEndDate = "2026-02-20"; // Missing time component

        // When/Then: Should return 400 Bad Request
        mockMvc.perform(withAuth(delete("/v1/roles/assignments/{assignmentId}", testAssignment.getId()))
                .param("endDate", invalidEndDate)
                .header("X-Correlation-Id", TEST_CORRELATION_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.correlationId").value(TEST_CORRELATION_ID));
    }

    @Test
    @DisplayName("Should accept endDate in the future")
    void testRevokeWithFutureEndDate() throws Exception {
        // Given: A future end date
        LocalDateTime futureDate = LocalDateTime.now(TEST_CLOCK).plusDays(1);
        String endDateParam = futureDate.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        // When/Then: Future date is supported
        mockMvc.perform(withAuth(delete("/v1/roles/assignments/{assignmentId}", testAssignment.getId()))
                .param("endDate", endDateParam)
                .header("X-Correlation-Id", TEST_CORRELATION_ID))
                .andExpect(status().isNoContent());

        RoleAssignment revoked = roleAssignmentRepository.findById(testAssignment.getId()).orElseThrow();
        assertThat(revoked.getEffectiveEndDate()).isEqualTo(futureDate);
    }

    @Test
    @DisplayName("Should return 404 for non-existent role assignment")
    void testRevokeNonExistentAssignment() throws Exception {
        // Given: A non-existent assignment ID
        UUID nonExistentId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        // When/Then: Should return 404 Not Found
        mockMvc.perform(withAuth(delete("/v1/roles/assignments/{assignmentId}", nonExistentId))
                .header("X-Correlation-Id", TEST_CORRELATION_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROLE_ASSIGNMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.correlationId").value(TEST_CORRELATION_ID));
    }
}
