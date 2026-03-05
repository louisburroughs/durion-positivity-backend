package com.positivity.accounting.controller;

import java.time.ZoneOffset;
import java.time.Clock;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import com.positivity.accounting.internal.dto.DefaultGLMappingListResponse;
import com.positivity.accounting.internal.dto.DefaultGLMappingRequest;
import com.positivity.accounting.internal.dto.DefaultGLMappingResponse;
import com.positivity.accounting.service.DefaultGLMappingService;

import tools.jackson.databind.ObjectMapper;

/**
 * Integration tests for DefaultGLMappingController.
 * Tests REST API endpoints for default GL mapping management.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("DefaultGLMappingController Tests")
class DefaultGLMappingControllerTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);


        @Autowired
        private WebApplicationContext context;

        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @MockitoBean
        private DefaultGLMappingService service;

        private static final String TEST_AUTHORITIES = String.join(",",
                        "accounting:default-mapping:create",
                        "accounting:default-mapping:edit",
                        "accounting:default-mapping:delete",
                        "accounting:default-mapping:view");

        // Test fixtures
        private static final UUID MAPPING_ID = UUID.fromString("a10217f9-3ec6-46b9-9c87-e7066c100c24");
        private static final UUID DEBIT_ACCOUNT_ID = UUID.fromString("b10217f9-3ec6-46b9-9c87-e7066c100c24");
        private static final UUID CREDIT_ACCOUNT_ID = UUID.fromString("c10217f9-3ec6-46b9-9c87-e7066c100c24");
        private static final UUID ORG_ID = UUID.fromString("d10217f9-3ec6-46b9-9c87-e7066c100c24");
        private static final String EVENT_TYPE = "INVOICE_CREATED";

        private DefaultGLMappingRequest validRequest;
        private DefaultGLMappingResponse validResponse;

        @BeforeEach
        void setUp() {
                // Initialize MockMvc with Spring Security
                this.mockMvc = webAppContextSetup(context)
                                .apply(springSecurity())
                                .build();

                validRequest = DefaultGLMappingRequest.builder()
                                .eventType(EVENT_TYPE)
                                .organizationId(ORG_ID)
                                .debitAccountId(DEBIT_ACCOUNT_ID)
                                .creditAccountId(CREDIT_ACCOUNT_ID)
                                .description("Default mapping for invoice creation")
                                .active(true)
                                .build();

                validResponse = DefaultGLMappingResponse.builder()
                                .mappingId(MAPPING_ID)
                                .eventType(EVENT_TYPE)
                                .organizationId(ORG_ID)
                                .debitAccountId(DEBIT_ACCOUNT_ID)
                                .debitAccountCode("1200")
                                .debitAccountName("Accounts Receivable")
                                .creditAccountId(CREDIT_ACCOUNT_ID)
                                .creditAccountCode("4000")
                                .creditAccountName("Revenue")
                                .description("Default mapping for invoice creation")
                                .active(true)
                                .createdAt(Instant.now(TEST_CLOCK))
                                .createdBy("test-user")
                                .modifiedAt(Instant.now(TEST_CLOCK))
                                .modifiedBy("test-user")
                                .build();
        }

        @Nested
        @DisplayName("POST /v1/accounting/default-mappings")
        class CreateDefaultMapping {

                @Test
                @DisplayName("should create default mapping successfully")
                void shouldCreateDefaultMappingSuccessfully() throws Exception {
                        // Arrange
                        when(service.createDefaultMapping(any(DefaultGLMappingRequest.class)))
                                        .thenReturn(validResponse);

                        // Act & Assert
                        mockMvc.perform(post("/v1/accounting/default-mappings")
                                        .header("X-Authorities", TEST_AUTHORITIES)
                                        .header("X-User", "test-user")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(validRequest)))
                                        .andExpect(status().isCreated())
                                        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                                        .andExpect(jsonPath("$.mappingId", is(MAPPING_ID.toString())))
                                        .andExpect(jsonPath("$.eventType", is(EVENT_TYPE)))
                                        .andExpect(jsonPath("$.organizationId", is(ORG_ID.toString())))
                                        .andExpect(jsonPath("$.debitAccountId", is(DEBIT_ACCOUNT_ID.toString())))
                                        .andExpect(jsonPath("$.debitAccountCode", is("1200")))
                                        .andExpect(jsonPath("$.creditAccountId", is(CREDIT_ACCOUNT_ID.toString())))
                                        .andExpect(jsonPath("$.creditAccountCode", is("4000")))
                                        .andExpect(jsonPath("$.active", is(true)));
                }

                @Test
                @DisplayName("should return 400 when GL account validation fails")
                void shouldReturn400WhenValidationFails() throws Exception {
                        // Arrange
                        when(service.createDefaultMapping(any(DefaultGLMappingRequest.class)))
                                        .thenThrow(new IllegalArgumentException("Debit account is not active"));

                        // Act & Assert
                        mockMvc.perform(post("/v1/accounting/default-mappings")
                                        .header("X-Authorities", TEST_AUTHORITIES)
                                        .header("X-User", "test-user")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(validRequest)))
                                        .andExpect(status().isBadRequest());
                }

                @Test
                @DisplayName("should create global mapping when organizationId is null")
                void shouldCreateGlobalMappingWhenOrgIsNull() throws Exception {
                        // Arrange
                        validRequest.setOrganizationId(null);
                        validResponse.setOrganizationId(null);
                        when(service.createDefaultMapping(any(DefaultGLMappingRequest.class)))
                                        .thenReturn(validResponse);

                        // Act & Assert
                        mockMvc.perform(post("/v1/accounting/default-mappings")
                                        .header("X-Authorities", TEST_AUTHORITIES)
                                        .header("X-User", "test-user")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(validRequest)))
                                        .andExpect(status().isCreated())
                                        .andExpect(jsonPath("$.organizationId").doesNotExist());
                }
        }

        @Nested
        @DisplayName("PUT /v1/accounting/default-mappings/{id}")
        class UpdateDefaultMapping {

                @Test
                @DisplayName("should update default mapping successfully")
                void shouldUpdateDefaultMappingSuccessfully() throws Exception {
                        // Arrange
                        DefaultGLMappingRequest updateRequest = DefaultGLMappingRequest.builder()
                                        .eventType("PAYMENT_RECEIVED")
                                        .organizationId(ORG_ID)
                                        .debitAccountId(DEBIT_ACCOUNT_ID)
                                        .creditAccountId(CREDIT_ACCOUNT_ID)
                                        .description("Updated description")
                                        .active(true)
                                        .build();

                        validResponse.setEventType("PAYMENT_RECEIVED");
                        validResponse.setDescription("Updated description");

                        when(service.updateDefaultMapping(eq(MAPPING_ID), any(DefaultGLMappingRequest.class)))
                                        .thenReturn(validResponse);

                        // Act & Assert
                        mockMvc.perform(put("/v1/accounting/default-mappings/{id}", MAPPING_ID)
                                        .header("X-Authorities", TEST_AUTHORITIES)
                                        .header("X-User", "test-user")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(updateRequest)))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.eventType", is("PAYMENT_RECEIVED")))
                                        .andExpect(jsonPath("$.description", is("Updated description")));
                }

                @Test
                @DisplayName("should return 404 when mapping not found")
                void shouldReturn404WhenMappingNotFound() throws Exception {
                        // Arrange
                        UUID unknownId = UUID.randomUUID();
                        when(service.updateDefaultMapping(eq(unknownId), any(DefaultGLMappingRequest.class)))
                                        .thenThrow(new IllegalArgumentException(
                                                        "Default GL mapping not found: " + unknownId));

                        // Act & Assert
                        mockMvc.perform(put("/v1/accounting/default-mappings/{id}", unknownId)
                                        .header("X-Authorities", TEST_AUTHORITIES)
                                        .header("X-User", "test-user")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(validRequest)))
                                        .andExpect(status().isBadRequest());
                }
        }

        @Nested
        @DisplayName("DELETE /v1/accounting/default-mappings/{id}")
        class DeactivateDefaultMapping {

                @Test
                @DisplayName("should deactivate default mapping successfully")
                void shouldDeactivateDefaultMappingSuccessfully() throws Exception {
                        // Arrange
                        doNothing().when(service).deactivateDefaultMapping(MAPPING_ID);

                        // Act & Assert
                        mockMvc.perform(delete("/v1/accounting/default-mappings/{id}", MAPPING_ID)
                                        .header("X-Authorities", TEST_AUTHORITIES)
                                        .header("X-User", "test-user"))
                                        .andExpect(status().isNoContent());
                }

                @Test
                @DisplayName("should return 404 when mapping not found for deactivation")
                void shouldReturn404WhenMappingNotFoundForDeactivation() throws Exception {
                        // Arrange
                        UUID unknownId = UUID.randomUUID();
                        doThrow(new IllegalArgumentException("Default GL mapping not found: " + unknownId))
                                        .when(service).deactivateDefaultMapping(unknownId);

                        // Act & Assert
                        mockMvc.perform(delete("/v1/accounting/default-mappings/{id}", unknownId)
                                        .header("X-Authorities", TEST_AUTHORITIES)
                                        .header("X-User", "test-user"))
                                        .andExpect(status().isBadRequest());
                }
        }

        @Nested
        @DisplayName("GET /v1/accounting/default-mappings/{id}")
        class GetDefaultMapping {

                @Test
                @DisplayName("should return default mapping by id")
                void shouldReturnDefaultMappingById() throws Exception {
                        // Arrange
                        when(service.getDefaultMapping(MAPPING_ID)).thenReturn(validResponse);

                        // Act & Assert
                        mockMvc.perform(get("/v1/accounting/default-mappings/{id}", MAPPING_ID)
                                        .header("X-Authorities", TEST_AUTHORITIES)
                                        .header("X-User", "test-user"))
                                        .andExpect(status().isOk())
                                        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                                        .andExpect(jsonPath("$.mappingId", is(MAPPING_ID.toString())))
                                        .andExpect(jsonPath("$.eventType", is(EVENT_TYPE)));
                }

                @Test
                @DisplayName("should return 404 when mapping not found")
                void shouldReturn404WhenMappingNotFound() throws Exception {
                        // Arrange
                        UUID unknownId = UUID.randomUUID();
                        when(service.getDefaultMapping(unknownId))
                                        .thenThrow(new IllegalArgumentException(
                                                        "Default GL mapping not found: " + unknownId));

                        // Act & Assert
                        mockMvc.perform(get("/v1/accounting/default-mappings/{id}", unknownId)
                                        .header("X-Authorities", TEST_AUTHORITIES)
                                        .header("X-User", "test-user"))
                                        .andExpect(status().isBadRequest());
                }
        }

        @Nested
        @DisplayName("GET /v1/accounting/default-mappings")
        class ListDefaultMappings {

                @Test
                @DisplayName("should return paginated list of default mappings")
                void shouldReturnPaginatedList() throws Exception {
                        // Arrange
                        DefaultGLMappingListResponse listResponse = DefaultGLMappingListResponse.builder()
                                        .mappings(List.of(validResponse))
                                        .page(0)
                                        .size(20)
                                        .totalElements(1)
                                        .totalPages(1)
                                        .build();

                        when(service.listDefaultMappings(0, 20)).thenReturn(listResponse);

                        // Act & Assert
                        mockMvc.perform(get("/v1/accounting/default-mappings")
                                        .header("X-Authorities", TEST_AUTHORITIES)
                                        .header("X-User", "test-user")
                                        .param("page", "0")
                                        .param("size", "20"))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.mappings", hasSize(1)))
                                        .andExpect(jsonPath("$.page", is(0)))
                                        .andExpect(jsonPath("$.size", is(20)))
                                        .andExpect(jsonPath("$.totalElements", is(1)))
                                        .andExpect(jsonPath("$.totalPages", is(1)));
                }

                @Test
                @DisplayName("should use default pagination params when not provided")
                void shouldUseDefaultPaginationParams() throws Exception {
                        // Arrange
                        DefaultGLMappingListResponse listResponse = DefaultGLMappingListResponse.builder()
                                        .mappings(List.of())
                                        .page(0)
                                        .size(20)
                                        .totalElements(0)
                                        .totalPages(0)
                                        .build();

                        when(service.listDefaultMappings(0, 20)).thenReturn(listResponse);

                        // Act & Assert
                        mockMvc.perform(get("/v1/accounting/default-mappings")
                                        .header("X-Authorities", TEST_AUTHORITIES)
                                        .header("X-User", "test-user"))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.page", is(0)))
                                        .andExpect(jsonPath("$.size", is(20)));
                }
        }

        @Nested
        @DisplayName("GET /v1/accounting/default-mappings/search")
        class SearchDefaultMappings {

                @Test
                @DisplayName("should search by event type")
                void shouldSearchByEventType() throws Exception {
                        // Arrange
                        when(service.findByEventType(EVENT_TYPE)).thenReturn(List.of(validResponse));

                        // Act & Assert
                        mockMvc.perform(get("/v1/accounting/default-mappings/search")
                                        .header("X-Authorities", TEST_AUTHORITIES)
                                        .header("X-User", "test-user")
                                        .param("eventType", EVENT_TYPE))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$", hasSize(1)))
                                        .andExpect(jsonPath("$[0].eventType", is(EVENT_TYPE)));
                }

                @Test
                @DisplayName("should search by organization id")
                void shouldSearchByOrganizationId() throws Exception {
                        // Arrange
                        when(service.findByOrganization(ORG_ID)).thenReturn(List.of(validResponse));

                        // Act & Assert
                        mockMvc.perform(get("/v1/accounting/default-mappings/search")
                                        .header("X-Authorities", TEST_AUTHORITIES)
                                        .header("X-User", "test-user")
                                        .param("organizationId", ORG_ID.toString()))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$", hasSize(1)))
                                        .andExpect(jsonPath("$[0].organizationId", is(ORG_ID.toString())));
                }

                @Test
                @DisplayName("should return global defaults when no filters provided")
                void shouldReturnGlobalDefaultsWhenNoFilters() throws Exception {
                        // Arrange
                        validResponse.setOrganizationId(null);
                        when(service.findAllGlobalDefaults()).thenReturn(List.of(validResponse));

                        // Act & Assert
                        mockMvc.perform(get("/v1/accounting/default-mappings/search")
                                        .header("X-Authorities", TEST_AUTHORITIES)
                                        .header("X-User", "test-user"))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$", hasSize(1)))
                                        .andExpect(jsonPath("$[0].organizationId").doesNotExist());
                }
        }

        @Nested
        @DisplayName("GET /v1/accounting/default-mappings/global")
        class ListGlobalDefaults {

                @Test
                @DisplayName("should return all global default mappings")
                void shouldReturnAllGlobalDefaults() throws Exception {
                        // Arrange
                        validResponse.setOrganizationId(null);
                        DefaultGLMappingResponse anotherGlobal = DefaultGLMappingResponse.builder()
                                        .mappingId(UUID.randomUUID())
                                        .eventType("REFUND_ISSUED")
                                        .organizationId(null)
                                        .debitAccountId(DEBIT_ACCOUNT_ID)
                                        .creditAccountId(CREDIT_ACCOUNT_ID)
                                        .active(true)
                                        .createdAt(Instant.now(TEST_CLOCK))
                                        .createdBy("system")
                                        .build();

                        when(service.findAllGlobalDefaults()).thenReturn(List.of(validResponse, anotherGlobal));

                        // Act & Assert
                        mockMvc.perform(get("/v1/accounting/default-mappings/global")
                                        .header("X-Authorities", TEST_AUTHORITIES)
                                        .header("X-User", "test-user"))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$", hasSize(2)))
                                        .andExpect(jsonPath("$[0].organizationId").doesNotExist())
                                        .andExpect(jsonPath("$[1].organizationId").doesNotExist());
                }

                @Test
                @DisplayName("should return empty list when no global defaults exist")
                void shouldReturnEmptyListWhenNoGlobalDefaults() throws Exception {
                        // Arrange
                        when(service.findAllGlobalDefaults()).thenReturn(List.of());

                        // Act & Assert
                        mockMvc.perform(get("/v1/accounting/default-mappings/global")
                                        .header("X-Authorities", TEST_AUTHORITIES)
                                        .header("X-User", "test-user"))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$", hasSize(0)));
                }
        }

        @Nested
        @DisplayName("GET /v1/accounting/default-mappings/resolve")
        class ResolveDefaultMapping {

                @Test
                @DisplayName("should resolve mapping for event type and organization")
                void shouldResolveMappingForEventTypeAndOrg() throws Exception {
                        // Arrange
                        when(service.findActiveDefaultForEvent(EVENT_TYPE, ORG_ID)).thenReturn(validResponse);

                        // Act & Assert
                        mockMvc.perform(get("/v1/accounting/default-mappings/resolve")
                                        .header("X-Authorities", TEST_AUTHORITIES)
                                        .header("X-User", "test-user")
                                        .param("eventType", EVENT_TYPE)
                                        .param("organizationId", ORG_ID.toString()))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.eventType", is(EVENT_TYPE)))
                                        .andExpect(jsonPath("$.organizationId", is(ORG_ID.toString())));
                }

                @Test
                @DisplayName("should resolve global mapping when no org-specific mapping exists")
                void shouldResolveGlobalMappingWhenNoOrgSpecific() throws Exception {
                        // Arrange
                        validResponse.setOrganizationId(null);
                        when(service.findActiveDefaultForEvent(EVENT_TYPE, ORG_ID)).thenReturn(validResponse);

                        // Act & Assert
                        mockMvc.perform(get("/v1/accounting/default-mappings/resolve")
                                        .header("X-Authorities", TEST_AUTHORITIES)
                                        .header("X-User", "test-user")
                                        .param("eventType", EVENT_TYPE)
                                        .param("organizationId", ORG_ID.toString()))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.eventType", is(EVENT_TYPE)))
                                        .andExpect(jsonPath("$.organizationId").doesNotExist());
                }

                @Test
                @DisplayName("should return 404 when no mapping found")
                void shouldReturn404WhenNoMappingFound() throws Exception {
                        // Arrange
                        when(service.findActiveDefaultForEvent("UNKNOWN_EVENT", ORG_ID)).thenReturn(null);

                        // Act & Assert
                        mockMvc.perform(get("/v1/accounting/default-mappings/resolve")
                                        .header("X-Authorities", TEST_AUTHORITIES)
                                        .header("X-User", "test-user")
                                        .param("eventType", "UNKNOWN_EVENT")
                                        .param("organizationId", ORG_ID.toString()))
                                        .andExpect(status().isNotFound());
                }

                @Test
                @DisplayName("should support resolution without organizationId")
                void shouldSupportResolutionWithoutOrganizationId() throws Exception {
                        // Arrange
                        validResponse.setOrganizationId(null);
                        when(service.findActiveDefaultForEvent(EVENT_TYPE, null)).thenReturn(validResponse);

                        // Act & Assert
                        mockMvc.perform(get("/v1/accounting/default-mappings/resolve")
                                        .header("X-Authorities", TEST_AUTHORITIES)
                                        .header("X-User", "test-user")
                                        .param("eventType", EVENT_TYPE))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.eventType", is(EVENT_TYPE)));
                }
        }
}
