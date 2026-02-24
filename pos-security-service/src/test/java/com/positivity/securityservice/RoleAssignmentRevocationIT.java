package com.positivity.securityservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.RoleAssignment;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.enums.ScopeType;
import com.positivity.securityservice.internal.repository.RoleAssignmentRepository;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.internal.service.TokenRevocationManager;
import com.positivity.shared.id.UUIDv7Generator;

/**
 * Integration tests for role assignment revocation functionality.
 * 
 * Tests the revocation behavior including:
 * - Immediate revocation (endDate = today)
 * - Scheduled revocation (endDate in future)
 * - Backdated revocation (endDate in past)
 * - Updating revocation (changing endDate)
 * - revokedAt timestamp behavior
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = PosSecurityServiceApplication.class)
@ActiveProfiles("test")
@DisplayName("Role Assignment Revocation Integration Tests")
class RoleAssignmentRevocationIT extends BaseIntegrationTest {

        @TestConfiguration
        static class TestConfig {
                @Bean
                @Primary
                public TokenRevocationManager tokenRevocationManager() {
                        // Mock TokenRevocationManager for tests where Redis is disabled
                        TokenRevocationManager mock = mock(TokenRevocationManager.class);
                        doNothing().when(mock).revokeToken(anyString(), anyLong());
                        when(mock.isRevoked(anyString())).thenReturn(false);
                        doNothing().when(mock).clearAllRevoked();
                        return mock;
                }
        }

        @Autowired
        private UserRepository userRepository;

        @Autowired
        private RoleRepository roleRepository;

        @Autowired
        private RoleAssignmentRepository roleAssignmentRepository;

        private User testUser;
        private Role testRole;

        @BeforeEach
        void setupTestData() {
                // Clean up any existing test data
                roleAssignmentRepository.deleteAll();
                userRepository.deleteAll();
                roleRepository.deleteAll();

                // Create test user
                testUser = new User();
                testUser.setId(UUIDv7Generator.generate());
                testUser.setUsername("test-user-" + System.currentTimeMillis());
                testUser.setPassword("password");
                testUser = userRepository.save(testUser);

                // Create test role
                testRole = new Role();
                testRole.setId(UUIDv7Generator.generate());
                testRole.setName("TEST_ROLE_" + System.currentTimeMillis());
                testRole.setDescription("Test role for revocation tests");
                testRole.setCreatedAt(Instant.now());
                testRole.setCreatedBy("test");
                testRole = roleRepository.save(testRole);
        }

        @Test
        @DisplayName("Should revoke assignment immediately with today's date")
        void testImmediateRevocation() throws Exception {
                // Given: Create a role assignment
                RoleAssignment assignment = createAssignment(testUser.getId(), testRole.getId(),
                                LocalDateTime.now(), null, ScopeType.GLOBAL);

                Instant beforeRevocation = Instant.now();

                // When: Revoke immediately (no endDate parameter = defaults to today)
                mockMvc.perform(
                                withAuth(delete("/v1/roles/assignments/" + assignment.getId()))
                                                .contentType(MediaType.APPLICATION_JSON))
                                .andExpect(status().isNoContent());

                Instant afterRevocation = Instant.now();

                // Then: Assignment should have effectiveEndDate set and revokedAt set
                RoleAssignment revokedAssignment = roleAssignmentRepository.findById(assignment.getId())
                                .orElseThrow();

                assertThat(revokedAssignment.getEffectiveEndDate()).isNotNull();
                assertThat(revokedAssignment.getRevokedAt()).isNotNull();
                assertThat(revokedAssignment.getRevokedAt()).isBetween(beforeRevocation, afterRevocation);
                assertThat(revokedAssignment.isEffective()).isFalse();
        }

        @Test
        @DisplayName("Should support scheduled revocation with future date")
        void testScheduledRevocation() throws Exception {
                // Given: Create a role assignment
                RoleAssignment assignment = createAssignment(testUser.getId(), testRole.getId(),
                                LocalDateTime.now(), null, ScopeType.GLOBAL);

                LocalDateTime futureDate = LocalDateTime.now().plusDays(30);
                Instant beforeRevocation = Instant.now();

                // When: Schedule revocation for future date
                mockMvc.perform(
                                withAuth(delete("/v1/roles/assignments/" + assignment.getId()))
                                                .param("endDate", futureDate.toString())
                                                .contentType(MediaType.APPLICATION_JSON))
                                .andExpect(status().isNoContent());

                Instant afterRevocation = Instant.now();

                // Then: Assignment should have future effectiveEndDate but revokedAt = now
                RoleAssignment revokedAssignment = roleAssignmentRepository.findById(assignment.getId())
                                .orElseThrow();

                assertThat(revokedAssignment.getEffectiveEndDate()).isEqualTo(futureDate);
                assertThat(revokedAssignment.getRevokedAt()).isNotNull();
                assertThat(revokedAssignment.getRevokedAt()).isBetween(beforeRevocation, afterRevocation);
                // Assignment should still be effective (endDate is in future)
                assertThat(revokedAssignment.isEffective()).isTrue();
        }

        @Test
        @DisplayName("Should support backdated revocation with past date")
        void testBackdatedRevocation() throws Exception {
                // Given: Create a role assignment
                RoleAssignment assignment = createAssignment(testUser.getId(), testRole.getId(),
                                LocalDateTime.now().minusDays(30), null, ScopeType.GLOBAL);

                LocalDateTime pastDate = LocalDateTime.now().minusDays(5);
                Instant beforeRevocation = Instant.now();

                // When: Revoke with past date
                mockMvc.perform(
                                withAuth(delete("/v1/roles/assignments/" + assignment.getId()))
                                                .param("endDate", pastDate.toString())
                                                .contentType(MediaType.APPLICATION_JSON))
                                .andExpect(status().isNoContent());

                Instant afterRevocation = Instant.now();

                // Then: Assignment should have past effectiveEndDate but revokedAt = now
                RoleAssignment revokedAssignment = roleAssignmentRepository.findById(assignment.getId())
                                .orElseThrow();

                assertThat(revokedAssignment.getEffectiveEndDate()).isEqualTo(pastDate);
                assertThat(revokedAssignment.getRevokedAt()).isNotNull();
                assertThat(revokedAssignment.getRevokedAt()).isBetween(beforeRevocation, afterRevocation);
                assertThat(revokedAssignment.isEffective()).isFalse();
        }

        @Test
        @DisplayName("Should update revokedAt when revocation is changed")
        void testUpdatingRevocation() throws Exception {
                // Given: Create and revoke an assignment
                RoleAssignment assignment = createAssignment(testUser.getId(), testRole.getId(),
                                LocalDateTime.now(), null, ScopeType.GLOBAL);

                LocalDateTime firstEndDate = LocalDateTime.now().plusDays(7);
                mockMvc.perform(
                                withAuth(delete("/v1/roles/assignments/" + assignment.getId()))
                                                .param("endDate", firstEndDate.toString())
                                                .contentType(MediaType.APPLICATION_JSON))
                                .andExpect(status().isNoContent());

                RoleAssignment firstRevocation = roleAssignmentRepository.findById(assignment.getId())
                                .orElseThrow();
                Instant firstRevokedAt = firstRevocation.getRevokedAt();

                // Wait a moment to ensure timestamp difference
                Awaitility.await().atMost(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                // When: Update the revocation with a different end date
                LocalDateTime secondEndDate = LocalDateTime.now().plusDays(14);
                Instant beforeSecondRevocation = Instant.now();

                mockMvc.perform(
                                withAuth(delete("/v1/roles/assignments/" + assignment.getId()))
                                                .param("endDate", secondEndDate.toString())
                                                .contentType(MediaType.APPLICATION_JSON))
                                .andExpect(status().isNoContent());

                Instant afterSecondRevocation = Instant.now();

                // Then: revokedAt should be updated to current timestamp
                RoleAssignment secondRevocation = roleAssignmentRepository.findById(assignment.getId())
                                .orElseThrow();

                assertThat(secondRevocation.getEffectiveEndDate()).isEqualTo(secondEndDate);
                assertThat(secondRevocation.getRevokedAt()).isNotNull();
                assertThat(secondRevocation.getRevokedAt()).isAfter(firstRevokedAt);
                assertThat(secondRevocation.getRevokedAt()).isBetween(beforeSecondRevocation, afterSecondRevocation);
        }

        @Test
        @DisplayName("Should return 404 when revoking non-existent assignment")
        void testRevokeNonExistentAssignment() throws Exception {
                UUID nonExistentId = UUID.randomUUID();

                mockMvc.perform(
                                withAuth(delete("/v1/roles/assignments/" + nonExistentId))
                                                .contentType(MediaType.APPLICATION_JSON))
                                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Should retrieve revoked assignments with includeHistory=true")
        void testRetrieveRevokedAssignments() throws Exception {
                // Given: Create and revoke an assignment
                RoleAssignment assignment = createAssignment(testUser.getId(), testRole.getId(),
                                LocalDateTime.now().minusDays(30), null, ScopeType.GLOBAL);

                mockMvc.perform(
                                withAuth(delete("/v1/roles/assignments/" + assignment.getId()))
                                                .param("endDate", LocalDateTime.now().minusDays(5).toString())
                                                .contentType(MediaType.APPLICATION_JSON))
                                .andExpect(status().isNoContent());

                // When: Retrieve assignments without history
                MvcResult withoutHistory = mockMvc.perform(
                                withAuth(get("/v1/roles/assignments/user/" + testUser.getId()))
                                                .param("includeHistory", "false")
                                                .contentType(MediaType.APPLICATION_JSON))
                                .andExpect(status().isOk())
                                .andReturn();

                // Then: Should not include revoked assignment
                String withoutHistoryJson = withoutHistory.getResponse().getContentAsString();
                assertThat(withoutHistoryJson).isEqualTo("[]");

                // When: Retrieve assignments with history
                MvcResult withHistory = mockMvc.perform(
                                withAuth(get("/v1/roles/assignments/user/" + testUser.getId()))
                                                .param("includeHistory", "true")
                                                .contentType(MediaType.APPLICATION_JSON))
                                .andExpect(status().isOk())
                                .andReturn();

                // Then: Should include revoked assignment
                String withHistoryJson = withHistory.getResponse().getContentAsString();
                assertThat(withHistoryJson).contains(assignment.getId().toString());
        }

        /**
         * Helper method to create a role assignment
         */
        private RoleAssignment createAssignment(UUID userId, UUID roleId,
                        LocalDateTime startDate, LocalDateTime endDate, ScopeType scopeType) {
                RoleAssignment assignment = new RoleAssignment();
                assignment.setId(UUIDv7Generator.generate());
                assignment.setUser(testUser);
                assignment.setRole(testRole);
                assignment.setEffectiveStartDate(startDate);
                // Note: setEffectiveEndDate will automatically set revokedAt if endDate is not
                // null
                assignment.setEffectiveEndDate(endDate);
                assignment.setScopeType(scopeType);
                assignment.setCreatedAt(Instant.now());
                assignment.setCreatedBy("test");
                return roleAssignmentRepository.save(assignment);
        }
}
