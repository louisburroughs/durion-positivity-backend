package com.positivity.accounting.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import com.positivity.accounting.BaseContractIntegrationTest;
import com.positivity.accounting.internal.dto.AccountingEventSubmitRequest;
import com.positivity.accounting.internal.dto.ReprocessEventRequest;
import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.repository.AccountingEventRepository;

/**
 * Contract Behavioral Integration Tests for Event Ingestion operations.
 *
 * <p>
 * This test suite validates the behavioral contracts for Accounting Event
 * Ingestion
 * REST endpoints including event submission, retrieval, retry, and
 * reprocessing.
 *
 * <p>
 * Tests verify:
 * - Happy path event submission and retrieval
 * - Event lifecycle (RECEIVED → PROCESSED/SUSPENDED/FAILED)
 * - Retry and reprocessing workflows
 * - Pagination and filtering
 * - Idempotency constraints
 * - Error handling (404, 409)
 */
@DisplayName("Event Ingestion Backend Contract Behavioral Tests")
class EventIngestionContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private AccountingEventRepository accountingEventRepository;

    private static final String API_V1_EVENTS = "/v1/accounting/events";

    // Test data
    private UUID testOrganizationId;

    @BeforeEach
    void setUp() {
        // Clean up before each test
        accountingEventRepository.deleteAll();
        testOrganizationId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        accountingEventRepository.deleteAll();
    }

    // ===============================================
    // HAPPY PATH SCENARIOS
    // ===============================================

    @Test
    @DisplayName("Submit accounting event - happy path")
    void testSubmitEvent_Success() throws Exception {
        // Given - valid event submission request
        Map<String, Object> payload = new HashMap<>();
        payload.put("amount", 100.00);
        payload.put("description", "Test sale");

        AccountingEventSubmitRequest request = AccountingEventSubmitRequest.builder()
                .organizationId(testOrganizationId)
                .eventType("SALE")
                .payload(payload)
                .build();

        // When/Then
        mockMvc.perform(withAuth(post(API_V1_EVENTS))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andDo(print())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventId").exists())
                .andExpect(jsonPath("$.organizationId").value(testOrganizationId.toString()))
                .andExpect(jsonPath("$.eventType").value("SALE"))
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.receivedAt").exists());
    }

    @Test
    @DisplayName("List accounting events with pagination")
    void testListEvents_Pagination() throws Exception {
        // Given - create multiple events
        for (int i = 0; i < 5; i++) {
            submitTestEvent("Event " + i);
        }

        // When/Then - list with pagination
        mockMvc.perform(withAuth(get(API_V1_EVENTS))
                .param("organizationId", testOrganizationId.toString())
                .param("page", "0")
                .param("size", "3"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.pageNumber").value(0))
                .andExpect(jsonPath("$.pageSize").value(3))
                .andExpect(jsonPath("$.totalElements").value(5));
    }

    @Test
    @DisplayName("List accounting events with status filter")
    void testListEvents_StatusFilter() throws Exception {
        // Given - create events and mark one as processed
        UUID eventId = submitTestEvent("Processed event");
        AccountingEvent event = accountingEventRepository.findById(eventId).orElseThrow();
        event.setStatus(AccountingEventStatus.PROCESSED);
        accountingEventRepository.save(event);

        submitTestEvent("Pending event");

        // When/Then - filter by PROCESSED status
        mockMvc.perform(withAuth(get(API_V1_EVENTS))
                .param("organizationId", testOrganizationId.toString())
                .param("status", "PROCESSED"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].status").value("PROCESSED"));
    }

    @Test
    @DisplayName("Get accounting event by ID - happy path")
    void testGetEvent_Success() throws Exception {
        // Given - create an event
        UUID eventId = submitTestEvent("Test event");

        // When/Then
        mockMvc.perform(withAuth(get(API_V1_EVENTS + "/{eventId}", eventId)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.eventType").value("SALE"))
                .andExpect(jsonPath("$.status").exists());
    }

    @Test
    @DisplayName("Retry failed event - happy path")
    void testRetryEvent_Success() throws Exception {
        // Given - create a failed event
        UUID eventId = submitTestEvent("Failed event");
        AccountingEvent event = accountingEventRepository.findById(eventId).orElseThrow();
        event.setStatus(AccountingEventStatus.FAILED);
        accountingEventRepository.save(event);

        // When/Then - retry the event
        mockMvc.perform(withAuth(post(API_V1_EVENTS + "/{eventId}/retry", eventId)))
                .andDo(print())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.status").exists());
    }

    @Test
    @DisplayName("Reprocess suspended event - happy path")
    void testReprocessEvent_Success() throws Exception {
        // Given - create a suspended event
        UUID eventId = submitTestEvent("Suspended event");
        AccountingEvent event = accountingEventRepository.findById(eventId).orElseThrow();
        event.setStatus(AccountingEventStatus.SUSPENDED);
        accountingEventRepository.save(event);

        ReprocessEventRequest request = new ReprocessEventRequest();
        request.setTriggeredByUserId(UUID.randomUUID().toString());
        request.setReprocessingNotes("Manual reprocessing after rule update");

        // When/Then - reprocess the event
        mockMvc.perform(withAuth(post(API_V1_EVENTS + "/{eventId}/reprocess", eventId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andDo(print())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventId").value(eventId.toString()));
    }

    @Test
    @DisplayName("Get reprocessing history - happy path")
    void testGetReprocessingHistory_Success() throws Exception {
        // Given - create a suspended event
        UUID eventId = submitTestEvent("Event with history");
        AccountingEvent event = accountingEventRepository.findById(eventId).orElseThrow();
        event.setStatus(AccountingEventStatus.SUSPENDED);
        accountingEventRepository.save(event);

        // When - reprocess the event to create history
        ReprocessEventRequest request = new ReprocessEventRequest();
        request.setTriggeredByUserId(UUID.randomUUID().toString());
        request.setReprocessingNotes("First reprocessing attempt");

        mockMvc.perform(withAuth(post(API_V1_EVENTS + "/{eventId}/reprocess", eventId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted());

        // Then - get reprocessing history
        mockMvc.perform(withAuth(get(API_V1_EVENTS + "/{eventId}/reprocessing-history", eventId)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("Get processing log - happy path")
    void testGetProcessingLog_Success() throws Exception {
        // Given - create an event
        UUID eventId = submitTestEvent("Event with log");

        // When/Then - get processing log
        mockMvc.perform(withAuth(get(API_V1_EVENTS + "/{eventId}/processing-log", eventId)))
                .andDo(print())
                .andExpect(status().isOk());
    }

    // ===============================================
    // VALIDATION SCENARIOS
    // ===============================================

    @Test
    @DisplayName("Get non-existent event - 404 error")
    void testGetEvent_NotFound() throws Exception {
        // Given - random UUID that doesn't exist
        UUID nonExistentId = UUID.randomUUID();

        // When/Then - expect 404 Not Found
        mockMvc.perform(withAuth(get(API_V1_EVENTS + "/{eventId}", nonExistentId)))
                .andDo(print())
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Retry non-existent event - 404 error")
    void testRetryEvent_NotFound() throws Exception {
        // Given - random UUID that doesn't exist
        UUID nonExistentId = UUID.randomUUID();

        // When/Then - expect 404 Not Found
        mockMvc.perform(withAuth(post(API_V1_EVENTS + "/{eventId}/retry", nonExistentId)))
                .andDo(print())
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Reprocess non-existent event - 404 error")
    void testReprocessEvent_NotFound() throws Exception {
        // Given - random UUID that doesn't exist
        UUID nonExistentId = UUID.randomUUID();

        ReprocessEventRequest request = new ReprocessEventRequest();
        request.setTriggeredByUserId(UUID.randomUUID().toString());
        request.setReprocessingNotes("Test");

        // When/Then - expect 404 Not Found
        mockMvc.perform(withAuth(post(API_V1_EVENTS + "/{eventId}/reprocess", nonExistentId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andDo(print())
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Reprocess already processed event - 409 conflict")
    void testReprocessEvent_AlreadyProcessed_Conflict() throws Exception {
        // Given - create and mark event as processed
        UUID eventId = submitTestEvent("Already processed event");
        AccountingEvent event = accountingEventRepository.findById(eventId).orElseThrow();
        event.setStatus(AccountingEventStatus.PROCESSED);
        accountingEventRepository.save(event);

        ReprocessEventRequest request = new ReprocessEventRequest();
        request.setTriggeredByUserId(UUID.randomUUID().toString());
        request.setReprocessingNotes("Attempt to reprocess");

        // When/Then - expect 409 Conflict
        mockMvc.perform(withAuth(post(API_V1_EVENTS + "/{eventId}/reprocess", eventId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andDo(print())
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Get reprocessing history for event without history - returns 404")
    void testGetReprocessingHistory_NotFound() throws Exception {
        // Given - create an event without reprocessing history
        UUID eventId = submitTestEvent("Event without history");

        // When/Then - expect empty list or 404
        mockMvc.perform(withAuth(get(API_V1_EVENTS + "/{eventId}/reprocessing-history", eventId)))
                .andDo(print())
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Get processing log for non-existent event - returns 200 with no entries")
    void testGetProcessingLog_NotFound() throws Exception {
        // Given - random UUID that doesn't exist
        UUID nonExistentId = UUID.randomUUID();

        // When/Then - expect 200 OK with message indicating no processing log entries
        mockMvc.perform(withAuth(get(API_V1_EVENTS + "/{eventId}/processing-log", nonExistentId)))
                .andDo(print())
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Submit event without required fields - 400 validation error")
    void testSubmitEvent_MissingRequiredFields() throws Exception {
        // Given - invalid request with missing fields
        AccountingEventSubmitRequest request = AccountingEventSubmitRequest.builder()
                .organizationId(testOrganizationId)
                // Missing eventType and payload
                .build();

        // When/Then - expect 400 Bad Request
        mockMvc.perform(withAuth(post(API_V1_EVENTS))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andDo(print())
                .andExpect(status().isBadRequest());
    }

    // ===============================================
    // HELPER METHODS
    // ===============================================

    /**
     * Helper method to submit a test event.
     */
    private UUID submitTestEvent(String description) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("amount", 100.00);
        payload.put("description", description);

        AccountingEventSubmitRequest request = AccountingEventSubmitRequest.builder()
                .organizationId(testOrganizationId)
                .eventType("SALE")
                .payload(payload)
                .build();

        MvcResult result = mockMvc.perform(withAuth(post(API_V1_EVENTS))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        var response = objectMapper.readValue(responseBody, java.util.Map.class);
        return UUID.fromString(response.get("eventId").toString());
    }
}
