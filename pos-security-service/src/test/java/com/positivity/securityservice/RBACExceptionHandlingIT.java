package com.positivity.securityservice;

import com.positivity.securityservice.internal.dto.ErrorResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for RBAC exception handling.
 * 
 * Tests that typed exceptions (RoleNotFoundException, UserNotFoundException,
 * RoleAssignmentNotFoundException, PermissionNotFoundException) are properly
 * mapped to HTTP 404 with ErrorResponse payload including correlationId.
 * 
 * @see com.positivity.securityservice.internal.config.GlobalExceptionHandler
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("RBAC Exception Handling Integration Tests")
class RBACExceptionHandlingIT extends BaseIntegrationTest {

        private static final String TEST_CORRELATION_ID = "rbac-test-correlation-id";

        /**
         * Test: GET /v1/roles/by-name/{name} with non-existent role
         * 
         * **Expected:** 404 NOT_FOUND with ErrorResponse {code="ROLE_NOT_FOUND",
         * correlationId}
         */
        @Test
        @DisplayName("Should return 404 with ROLE_NOT_FOUND when role does not exist")
        void testGetRoleByName_NotFound() throws Exception {
                String nonExistentRoleName = "NON_EXISTENT_ROLE_"
                                + UUID.fromString("00000000-0000-0000-0000-000000000001");

                MvcResult result = mockMvc.perform(
                                withAuth(get("/v1/roles/by-name/" + nonExistentRoleName))
                                                .header("X-Correlation-Id", TEST_CORRELATION_ID)
                                                .contentType(MediaType.APPLICATION_JSON))
                                .andExpect(status().isNotFound())
                                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                                .andExpect(jsonPath("$.code").value("ROLE_NOT_FOUND"))
                                .andExpect(jsonPath("$.message").exists())
                                .andExpect(jsonPath("$.timestamp").exists())
                                .andExpect(jsonPath("$.correlationId").value(TEST_CORRELATION_ID))
                                .andReturn();

                ErrorResponse errorResponse = objectMapper.readValue(
                                result.getResponse().getContentAsString(),
                                ErrorResponse.class);

                assertThat(errorResponse.code()).isEqualTo("ROLE_NOT_FOUND");
                assertThat(errorResponse.message()).contains("Role not found");
                assertThat(errorResponse.correlationId()).isEqualTo(TEST_CORRELATION_ID);
                assertThat(errorResponse.timestamp()).isNotNull();
        }

        /**
         * Test: GET /v1/roles/assignments/user/{userId} with non-existent user
         * 
         * **Expected:** 404 NOT_FOUND with ErrorResponse {code="USER_NOT_FOUND",
         * correlationId}
         */
        @Test
        @DisplayName("Should return 404 with USER_NOT_FOUND when user does not exist")
        void testGetUserRoleAssignments_UserNotFound() throws Exception {
                UUID nonExistentUserId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                MvcResult result = mockMvc.perform(
                                withAuth(get("/v1/roles/assignments/user/" + nonExistentUserId))
                                                .header("X-Correlation-Id", TEST_CORRELATION_ID)
                                                .contentType(MediaType.APPLICATION_JSON))
                                .andExpect(status().isNotFound())
                                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                                .andExpect(jsonPath("$.message").exists())
                                .andExpect(jsonPath("$.timestamp").exists())
                                .andExpect(jsonPath("$.correlationId").value(TEST_CORRELATION_ID))
                                .andReturn();

                ErrorResponse errorResponse = objectMapper.readValue(
                                result.getResponse().getContentAsString(),
                                ErrorResponse.class);

                assertThat(errorResponse.code()).isEqualTo("USER_NOT_FOUND");
                assertThat(errorResponse.message()).contains("User not found");
                assertThat(errorResponse.correlationId()).isEqualTo(TEST_CORRELATION_ID);
                assertThat(errorResponse.timestamp()).isNotNull();
        }

        /**
         * Test: GET /v1/roles/permissions/user/{userId} with non-existent user
         * 
         * **Expected:** 404 NOT_FOUND with ErrorResponse {code="USER_NOT_FOUND",
         * correlationId}
         */
        @Test
        @DisplayName("Should return 404 with USER_NOT_FOUND when getting permissions for non-existent user")
        void testGetUserPermissions_UserNotFound() throws Exception {
                UUID nonExistentUserId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                MvcResult result = mockMvc.perform(
                                withAuth(get("/v1/roles/permissions/user/" + nonExistentUserId))
                                                .header("X-Correlation-Id", TEST_CORRELATION_ID)
                                                .contentType(MediaType.APPLICATION_JSON))
                                .andExpect(status().isNotFound())
                                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                                .andExpect(jsonPath("$.message").exists())
                                .andExpect(jsonPath("$.timestamp").exists())
                                .andExpect(jsonPath("$.correlationId").value(TEST_CORRELATION_ID))
                                .andReturn();

                ErrorResponse errorResponse = objectMapper.readValue(
                                result.getResponse().getContentAsString(),
                                ErrorResponse.class);

                assertThat(errorResponse.code()).isEqualTo("USER_NOT_FOUND");
                assertThat(errorResponse.message()).contains("User not found");
                assertThat(errorResponse.correlationId()).isEqualTo(TEST_CORRELATION_ID);
        }

        /**
         * Test: GET /v1/roles/check-permission with non-existent user
         * 
         * **Expected:** 404 NOT_FOUND with ErrorResponse {code="USER_NOT_FOUND",
         * correlationId}
         */
        @Test
        @DisplayName("Should return 404 with USER_NOT_FOUND when checking permission for non-existent user")
        void testCheckUserPermission_UserNotFound() throws Exception {
                UUID nonExistentUserId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                MvcResult result = mockMvc.perform(
                                withAuth(get("/v1/roles/check-permission")
                                                .header("X-Correlation-Id", TEST_CORRELATION_ID)
                                                .param("userId", nonExistentUserId.toString())
                                                .param("permission", "some:permission")
                                                .contentType(MediaType.APPLICATION_JSON)))
                                .andExpect(status().isNotFound())
                                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                                .andExpect(jsonPath("$.message").exists())
                                .andExpect(jsonPath("$.timestamp").exists())
                                .andExpect(jsonPath("$.correlationId").value(TEST_CORRELATION_ID))
                                .andReturn();

                ErrorResponse errorResponse = objectMapper.readValue(
                                result.getResponse().getContentAsString(),
                                ErrorResponse.class);

                assertThat(errorResponse.code()).isEqualTo("USER_NOT_FOUND");
                assertThat(errorResponse.correlationId()).isEqualTo(TEST_CORRELATION_ID);
        }
}
