package com.positivity.accounting.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.accounting.internal.controller.DefaultGLMappingController;
import com.positivity.accounting.internal.dto.DefaultGLMappingListResponse;
import com.positivity.accounting.internal.dto.DefaultGLMappingRequest;
import com.positivity.accounting.internal.dto.DefaultGLMappingResponse;
import com.positivity.accounting.service.DefaultGLMappingService;

/**
 * Integration tests for DefaultGLMappingController
 * 
 * Tests REST API endpoints for default GL mapping management.
 */
@WebMvcTest(DefaultGLMappingController.class)
@ContextConfiguration(classes = { DefaultGLMappingController.class })
@DisplayName("DefaultGLMappingController Integration Tests")
class DefaultGLMappingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private DefaultGLMappingService defaultGLMappingService;

    private UUID mappingId;
    private UUID organizationId;
    private UUID debitAccountId;
    private UUID creditAccountId;
    private DefaultGLMappingRequest testRequest;
    private DefaultGLMappingResponse testResponse;

    @BeforeEach
    void setUp() {
        mappingId = UUID.randomUUID();
        organizationId = UUID.randomUUID();
        debitAccountId = UUID.randomUUID();
        creditAccountId = UUID.randomUUID();

        testRequest = DefaultGLMappingRequest.builder()
                .eventType("billing.invoicePosted")
                .organizationId(organizationId)
                .debitAccountId(debitAccountId)
                .creditAccountId(creditAccountId)
                .description("Test default mapping")
                .active(true)
                .build();

        testResponse = DefaultGLMappingResponse.builder()
                .mappingId(mappingId)
                .eventType("billing.invoicePosted")
                .organizationId(organizationId)
                .debitAccountId(debitAccountId)
                .debitAccountCode("1100")
                .debitAccountName("Accounts Receivable")
                .creditAccountId(creditAccountId)
                .creditAccountCode("4000")
                .creditAccountName("Revenue")
                .description("Test default mapping")
                .active(true)
                .createdAt(Instant.now())
                .createdBy("test-user")
                .modifiedAt(Instant.now())
                .modifiedBy("test-user")
                .build();
    }

    @Nested
    @DisplayName("POST /v1/accounting/default-mappings")
    class CreateDefaultMappingTests {

        @Test
        @WithMockUser(authorities = "accounting:default-mapping:create")
        @DisplayName("Should create default mapping successfully")
        void shouldCreateDefaultMappingSuccessfully() throws Exception {
            // Arrange
            when(defaultGLMappingService.createDefaultMapping(any(DefaultGLMappingRequest.class)))
                    .thenReturn(testResponse);

            // Act & Assert
            mockMvc.perform(post("/v1/accounting/default-mappings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(testRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.mappingId", is(mappingId.toString())))
                    .andExpect(jsonPath("$.eventType", is("billing.invoicePosted")))
                    .andExpect(jsonPath("$.debitAccountCode", is("1100")))
                    .andExpect(jsonPath("$.creditAccountCode", is("4000")))
                    .andExpect(jsonPath("$.active", is(true)));
        }

        @Test
        @WithMockUser(authorities = "accounting:default-mapping:create")
        @DisplayName("Should return 400 when request is invalid")
        void shouldReturn400WhenRequestIsInvalid() throws Exception {
            // Arrange - missing required field
            DefaultGLMappingRequest invalidRequest = DefaultGLMappingRequest.builder()
                    .eventType(null) // Missing required field
                    .debitAccountId(debitAccountId)
                    .creditAccountId(creditAccountId)
                    .build();

            // Act & Assert
            mockMvc.perform(post("/v1/accounting/default-mappings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(invalidRequest)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Should return 403 when user lacks permission")
        void shouldReturn403WhenUserLacksPermission() throws Exception {
            // Act & Assert
            mockMvc.perform(post("/v1/accounting/default-mappings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(testRequest)))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("PUT /v1/accounting/default-mappings/{id}")
    class UpdateDefaultMappingTests {

        @Test
        @WithMockUser(authorities = "accounting:default-mapping:edit")
        @DisplayName("Should update default mapping successfully")
        void shouldUpdateDefaultMappingSuccessfully() throws Exception {
            // Arrange
            when(defaultGLMappingService.updateDefaultMapping(eq(mappingId), any(DefaultGLMappingRequest.class)))
                    .thenReturn(testResponse);

            // Act & Assert
            mockMvc.perform(put("/v1/accounting/default-mappings/{id}", mappingId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(testRequest)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mappingId", is(mappingId.toString())))
                    .andExpect(jsonPath("$.eventType", is("billing.invoicePosted")));
        }

        @Test
        @WithMockUser(authorities = "accounting:default-mapping:edit")
        @DisplayName("Should return 404 when mapping not found")
        void shouldReturn404WhenMappingNotFound() throws Exception {
            // Arrange
            when(defaultGLMappingService.updateDefaultMapping(eq(mappingId), any(DefaultGLMappingRequest.class)))
                    .thenThrow(new IllegalArgumentException("Default GL mapping not found"));

            // Act & Assert
            mockMvc.perform(put("/v1/accounting/default-mappings/{id}", mappingId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(testRequest)))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("DELETE /v1/accounting/default-mappings/{id}")
    class DeactivateDefaultMappingTests {

        @Test
        @WithMockUser(authorities = "accounting:default-mapping:delete")
        @DisplayName("Should deactivate default mapping successfully")
        void shouldDeactivateDefaultMappingSuccessfully() throws Exception {
            // Arrange
            doNothing().when(defaultGLMappingService).deactivateDefaultMapping(mappingId);

            // Act & Assert
            mockMvc.perform(delete("/v1/accounting/default-mappings/{id}", mappingId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @WithMockUser(authorities = "accounting:default-mapping:delete")
        @DisplayName("Should return 404 when mapping not found for deactivation")
        void shouldReturn404WhenMappingNotFoundForDeactivation() throws Exception {
            // Arrange
            doThrow(new IllegalArgumentException("Default GL mapping not found"))
                    .when(defaultGLMappingService).deactivateDefaultMapping(mappingId);

            // Act & Assert
            mockMvc.perform(delete("/v1/accounting/default-mappings/{id}", mappingId))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /v1/accounting/default-mappings/{id}")
    class GetDefaultMappingTests {

        @Test
        @WithMockUser(authorities = "accounting:default-mapping:view")
        @DisplayName("Should get default mapping by ID successfully")
        void shouldGetDefaultMappingByIdSuccessfully() throws Exception {
            // Arrange
            when(defaultGLMappingService.getDefaultMapping(mappingId)).thenReturn(testResponse);

            // Act & Assert
            mockMvc.perform(get("/v1/accounting/default-mappings/{id}", mappingId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mappingId", is(mappingId.toString())))
                    .andExpect(jsonPath("$.eventType", is("billing.invoicePosted")))
                    .andExpect(jsonPath("$.debitAccountCode", is("1100")))
                    .andExpect(jsonPath("$.creditAccountCode", is("4000")));
        }

        @Test
        @WithMockUser(authorities = "accounting:default-mapping:view")
        @DisplayName("Should return 404 when mapping not found")
        void shouldReturn404WhenMappingNotFound() throws Exception {
            // Arrange
            when(defaultGLMappingService.getDefaultMapping(mappingId))
                    .thenThrow(new IllegalArgumentException("Default GL mapping not found"));

            // Act & Assert
            mockMvc.perform(get("/v1/accounting/default-mappings/{id}", mappingId))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /v1/accounting/default-mappings")
    class ListDefaultMappingsTests {

        @Test
        @WithMockUser(authorities = "accounting:default-mapping:view")
        @DisplayName("Should list default mappings with pagination")
        void shouldListDefaultMappingsWithPagination() throws Exception {
            // Arrange
            DefaultGLMappingListResponse listResponse = DefaultGLMappingListResponse.builder()
                    .mappings(List.of(testResponse))
                    .page(0)
                    .size(20)
                    .totalElements(1)
                    .totalPages(1)
                    .build();

            when(defaultGLMappingService.listDefaultMappings(0, 20)).thenReturn(listResponse);

            // Act & Assert
            mockMvc.perform(get("/v1/accounting/default-mappings")
                    .param("page", "0")
                    .param("size", "20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.mappings", hasSize(1)))
                    .andExpect(jsonPath("$.page", is(0)))
                    .andExpect(jsonPath("$.size", is(20)))
                    .andExpect(jsonPath("$.totalElements", is(1)));
        }
    }

    @Nested
    @DisplayName("GET /v1/accounting/default-mappings/search")
    class SearchDefaultMappingsTests {

        @Test
        @WithMockUser(authorities = "accounting:default-mapping:view")
        @DisplayName("Should search by event type")
        void shouldSearchByEventType() throws Exception {
            // Arrange
            when(defaultGLMappingService.findByEventType("billing.invoicePosted"))
                    .thenReturn(List.of(testResponse));

            // Act & Assert
            mockMvc.perform(get("/v1/accounting/default-mappings/search")
                    .param("eventType", "billing.invoicePosted"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].eventType", is("billing.invoicePosted")));
        }

        @Test
        @WithMockUser(authorities = "accounting:default-mapping:view")
        @DisplayName("Should search by organization ID")
        void shouldSearchByOrganizationId() throws Exception {
            // Arrange
            when(defaultGLMappingService.findByOrganization(organizationId))
                    .thenReturn(List.of(testResponse));

            // Act & Assert
            mockMvc.perform(get("/v1/accounting/default-mappings/search")
                    .param("organizationId", organizationId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].organizationId", is(organizationId.toString())));
        }
    }

    @Nested
    @DisplayName("GET /v1/accounting/default-mappings/global")
    class ListGlobalDefaultsTests {

        @Test
        @WithMockUser(authorities = "accounting:default-mapping:view")
        @DisplayName("Should list all global defaults")
        void shouldListAllGlobalDefaults() throws Exception {
            // Arrange
            DefaultGLMappingResponse globalResponse = DefaultGLMappingResponse.builder()
                    .mappingId(UUID.randomUUID())
                    .eventType("billing.invoicePosted")
                    .organizationId(null)
                    .debitAccountId(debitAccountId)
                    .creditAccountId(creditAccountId)
                    .active(true)
                    .build();

            when(defaultGLMappingService.findAllGlobalDefaults()).thenReturn(List.of(globalResponse));

            // Act & Assert
            mockMvc.perform(get("/v1/accounting/default-mappings/global"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].organizationId").doesNotExist());
        }
    }

    @Nested
    @DisplayName("GET /v1/accounting/default-mappings/resolve")
    class ResolveDefaultMappingTests {

        @Test
        @WithMockUser(authorities = "accounting:default-mapping:view")
        @DisplayName("Should resolve default mapping for event type and organization")
        void shouldResolveDefaultMappingForEventTypeAndOrganization() throws Exception {
            // Arrange
            when(defaultGLMappingService.findActiveDefaultForEvent("billing.invoicePosted", organizationId))
                    .thenReturn(testResponse);

            // Act & Assert
            mockMvc.perform(get("/v1/accounting/default-mappings/resolve")
                    .param("eventType", "billing.invoicePosted")
                    .param("organizationId", organizationId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.eventType", is("billing.invoicePosted")))
                    .andExpect(jsonPath("$.organizationId", is(organizationId.toString())));
        }

        @Test
        @WithMockUser(authorities = "accounting:default-mapping:view")
        @DisplayName("Should return 404 when no default mapping found")
        void shouldReturn404WhenNoDefaultMappingFound() throws Exception {
            // Arrange
            when(defaultGLMappingService.findActiveDefaultForEvent("billing.unknownEvent", organizationId))
                    .thenReturn(null);

            // Act & Assert
            mockMvc.perform(get("/v1/accounting/default-mappings/resolve")
                    .param("eventType", "billing.unknownEvent")
                    .param("organizationId", organizationId.toString()))
                    .andExpect(status().isNotFound());
        }
    }
}
