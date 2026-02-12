package com.positivity.accounting;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import com.positivity.accounting.internal.dto.AccountingEventSubmitRequest;
import com.positivity.accounting.internal.dto.ReprocessEventRequest;
import com.positivity.accounting.internal.entity.AccountingEvent;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.repository.AccountingEventRepository;

import java.time.Instant;

/**
 * Contract Behavioral Integration Tests for Suspense Queue Reprocessing
 * (CAP:055)
 * 
 * This test suite validates the behavioral contracts defined in:
 * durion/domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md
 * 
 * Contract Status: draft
 * Scope: Suspense queue reprocessing, idempotency, attempt history
 * 
 * Each test maps to an acceptance criterion defined in issue #122.
 */
@DisplayName("Suspense Queue Reprocessing Contract Behavioral Tests (CAP:055)")
public class SuspenseQueueContractBehaviorIT extends BaseIntegrationTest {

        @Autowired
        private AccountingEventRepository accountingEventRepository;

        private static final String API_V1 = "/v1/accounting/events";

        // ===============================================
        // HAPPY PATH SCENARIOS
        // ===============================================

        @Test
        @DisplayName("AC-2a: Successful reprocess posts and closes entry (200 OK)")
        public void testReprocessSuspendedEventSuccessful() throws Exception {
                // Arrange: Create a suspended event
                AccountingEventSubmitRequest submitRequest = new AccountingEventSubmitRequest();
                submitRequest.setEventType("INVOICE_RECEIVED");
                submitRequest.setOrganizationId(UUID.randomUUID());
                submitRequest.setSourceSystem("TEST_SYSTEM");
                submitRequest.setTransactionDate(LocalDateTime.now());
                submitRequest.setPayload(Map.of(
                                "invoiceId", "INV-001",
                                "amount", 100.00,
                                "description", "Test invoice"));

                MvcResult submitResult = mockMvc.perform(withAuth(post(API_V1))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(submitRequest)))
                                .andExpect(status().isAccepted())
                                .andReturn();

                String eventId = extractEventIdFromResponse(submitResult);

                // Manually mark event as SUSPENDED for this test
                // In production, this would happen through the posting rule engine
                markEventAsSuspended(UUID.fromString(eventId), "Test setup: simulating rule engine suspension");

                // Act: Reprocess the suspended event
                ReprocessEventRequest reprocessRequest = ReprocessEventRequest.builder()
                                .triggeredByUserId("test-admin")
                                .reprocessingNotes("Testing reprocess after rule correction")
                                .build();

                // Assert: Successful reprocessing returns 200 OK with PROCESSED status
                mockMvc.perform(withAuth(post(API_V1 + "/{eventId}/reprocess", eventId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(reprocessRequest)))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("PROCESSED"));
        }

        @Test
        @DisplayName("AC-2b: Reprocess accepted but still pending (202 Accepted)")
        public void testReprocessSuspendedEventAccepted() throws Exception {
                // Arrange: Create a suspended event
                AccountingEventSubmitRequest submitRequest = new AccountingEventSubmitRequest();
                submitRequest.setEventType("INVOICE_RECEIVED");
                submitRequest.setOrganizationId(UUID.randomUUID());
                submitRequest.setSourceSystem("TEST_SYSTEM");
                submitRequest.setTransactionDate(LocalDateTime.now());
                submitRequest.setPayload(Map.of(
                                "invoiceId", "INV-001-PENDING",
                                "amount", 100.00,
                                "description", "Test invoice for pending reprocess"));

                MvcResult submitResult = mockMvc.perform(withAuth(post(API_V1))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(submitRequest)))
                                .andExpect(status().isAccepted())
                                .andReturn();

                String eventId = extractEventIdFromResponse(submitResult);

                // Manually mark event as SUSPENDED for this test
                // In production, this would happen through the posting rule engine
                markEventAsSuspended(UUID.fromString(eventId), "Test setup: simulating pending reprocess");

                // Act: Reprocess the suspended event
                ReprocessEventRequest reprocessRequest = ReprocessEventRequest.builder()
                                .triggeredByUserId("test-admin")
                                .reprocessingNotes("Testing reprocess that remains pending")
                                .build();

                // Assert: Reprocessing accepted but not yet completed returns 202 Accepted
                mockMvc.perform(withAuth(post(API_V1 + "/{eventId}/reprocess", eventId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(reprocessRequest)))
                                .andDo(print())
                                .andExpect(status().isAccepted())
                                .andExpect(jsonPath("$.status").value("SUSPENDED"));
        }

        @Test
        @DisplayName("AC-3: Idempotent reprocess returns 409 for PROCESSED events")
        public void testReprocessIdempotency() throws Exception {
                // This test validates BR-3: Idempotency rule
                // Reprocessing a PROCESSED event should return 409 Conflict

                // Arrange: Create a PROCESSED event
                UUID processedEventId = createProcessedEvent();

                // Act: Attempt to reprocess a PROCESSED event
                ReprocessEventRequest reprocessRequest = ReprocessEventRequest.builder()
                                .triggeredByUserId("test-admin")
                                .reprocessingNotes("Testing idempotency")
                                .build();

                // Assert: Expected 409 Conflict
                mockMvc.perform(withAuth(post(API_V1 + "/{eventId}/reprocess", processedEventId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(reprocessRequest)))
                                .andDo(print())
                                .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("AC-4a: Attempt history is maintained (successful reprocess)")
        public void testReprocessingAttemptHistoryMaintainedSuccess() throws Exception {
                // Arrange: Create a suspended event
                AccountingEventSubmitRequest submitRequest = new AccountingEventSubmitRequest();
                submitRequest.setEventType("INVOICE_RECEIVED");
                submitRequest.setOrganizationId(UUID.randomUUID());
                submitRequest.setSourceSystem("TEST_SYSTEM");
                submitRequest.setTransactionDate(LocalDateTime.now());
                submitRequest.setPayload(Map.of(
                                "invoiceId", "INV-002",
                                "amount", 200.00,
                                "description", "Test invoice for history tracking - successful"));

                MvcResult submitResult = mockMvc.perform(withAuth(post(API_V1))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(submitRequest)))
                                .andExpect(status().isAccepted())
                                .andReturn();

                String eventId = extractEventIdFromResponse(submitResult);

                // Act: Reprocess the event
                ReprocessEventRequest reprocessRequest = ReprocessEventRequest.builder()
                                .triggeredByUserId("test-admin")
                                .build();

                mockMvc.perform(withAuth(post(API_V1 + "/{eventId}/reprocess", eventId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(reprocessRequest)))
                                .andExpect(status().isOk());

                // Assert: Reprocessing history should be retrievable
                mockMvc.perform(withAuth(get(API_V1 + "/{eventId}/reprocessing-history", eventId)))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$").isArray())
                                .andExpect(jsonPath("$[0].triggeredByUserId").value("test-admin"))
                                .andExpect(jsonPath("$[0].outcome").value("SUCCESS"));
        }

        @Test
        @DisplayName("AC-4b: Attempt history is maintained (failed reprocess)")
        public void testReprocessingAttemptHistoryMaintainedFailure() throws Exception {
                // Arrange: Create a suspended event
                AccountingEventSubmitRequest submitRequest = new AccountingEventSubmitRequest();
                submitRequest.setEventType("INVOICE_RECEIVED");
                submitRequest.setOrganizationId(UUID.randomUUID());
                submitRequest.setSourceSystem("TEST_SYSTEM");
                submitRequest.setTransactionDate(LocalDateTime.now());
                submitRequest.setPayload(Map.of(
                                "invoiceId", "INV-002-FAIL",
                                "amount", 200.00,
                                "description", "Test invoice for history tracking - failed"));

                MvcResult submitResult = mockMvc.perform(withAuth(post(API_V1))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(submitRequest)))
                                .andExpect(status().isAccepted())
                                .andReturn();

                String eventId = extractEventIdFromResponse(submitResult);

                // Act: Reprocess the event
                ReprocessEventRequest reprocessRequest = ReprocessEventRequest.builder()
                                .triggeredByUserId("test-admin")
                                .build();

                mockMvc.perform(withAuth(post(API_V1 + "/{eventId}/reprocess", eventId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(reprocessRequest)))
                                .andExpect(status().isAccepted());

                // Assert: Reprocessing history should be retrievable with failure outcome
                mockMvc.perform(withAuth(get(API_V1 + "/{eventId}/reprocessing-history", eventId)))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$").isArray())
                                .andExpect(jsonPath("$[0].triggeredByUserId").value("test-admin"))
                                .andExpect(jsonPath("$[0].outcome").value("FAILURE"));
        }

        // ===============================================
        // ERROR SCENARIOS
        // ===============================================

        @Test
        @DisplayName("Reprocess returns 404 for non-existent event")
        public void testReprocessNonExistentEventNotFound() throws Exception {
                // Arrange: Use a valid UUID format that doesn't exist in database
                UUID nonExistentId = UUID.randomUUID();

                // Act: Attempt to reprocess
                ReprocessEventRequest reprocessRequest = ReprocessEventRequest.builder()
                                .triggeredByUserId("test-admin")
                                .build();

                // Assert: Should return 404 Not Found
                mockMvc.perform(withAuth(post(API_V1 + "/{eventId}/reprocess", nonExistentId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(reprocessRequest)))
                                .andDo(print())
                                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Reprocess returns 400 for invalid event ID format")
        public void testReprocessInvalidEventIdBadRequest() throws Exception {
                // Arrange: Use an invalid ID that fails validation
                String invalidId = "not-a-valid-uuid";

                // Act: Attempt to reprocess with invalid UUID format
                ReprocessEventRequest reprocessRequest = ReprocessEventRequest.builder()
                                .triggeredByUserId("test-admin")
                                .build();

                // Assert: Should return 400 Bad Request
                mockMvc.perform(withAuth(post(API_V1 + "/{eventId}/reprocess", invalidId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(reprocessRequest)))
                                .andDo(print())
                                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Reprocess requires triggeredByUserId")
        public void testReprocessRequiresUserId() throws Exception {
                // Arrange: Create request without triggeredByUserId
                ReprocessEventRequest invalidRequest = ReprocessEventRequest.builder()
                                .reprocessingNotes("Missing user ID")
                                .build();

                UUID eventId = UUID.randomUUID();

                // Act & Assert: Should return 400 Bad Request
                mockMvc.perform(withAuth(post(API_V1 + "/{eventId}/reprocess", eventId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(invalidRequest)))
                                .andDo(print())
                                .andExpect(status().isBadRequest());
        }

        // ===============================================
        // HELPER METHODS
        // ===============================================

        /**
         * Extracts the event ID from a submit event response.
         */
        private String extractEventIdFromResponse(MvcResult result) throws Exception {
                String responseBody = result.getResponse().getContentAsString();
                Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
                return (String) responseMap.get("eventId");
        }

        /**
         * Helper method to mark an event as SUSPENDED for test purposes.
         * In production, this would be done by the posting rule engine.
         */
        private void markEventAsSuspended(UUID eventId, String errorMessage) {
                AccountingEvent event = accountingEventRepository.findById(eventId)
                                .orElseThrow(() -> new IllegalStateException("Event not found: " + eventId));
                event.setStatus(AccountingEventStatus.SUSPENDED);
                event.setErrorMessage(errorMessage);
                accountingEventRepository.save(event);
        }

        /**
         * Helper method to create a fully PROCESSED event for testing idempotency.
         * Returns the event ID of the created event.
         */
        private UUID createProcessedEvent() {
                AccountingEvent event = new AccountingEvent();
                event.setEventType("INVOICE_RECEIVED");
                event.setOrganizationId(UUID.randomUUID());
                event.setTransactionDate(LocalDateTime.now());
                event.setPayload(Map.of(
                                "invoiceId", "INV-PROCESSED",
                                "amount", 100.00,
                                "description", "Already processed invoice"));
                event.setStatus(AccountingEventStatus.PROCESSED);
                event.setProcessedAt(Instant.now());
                event.setJournalEntryId(UUID.randomUUID()); // Simulate JE was created

                AccountingEvent saved = accountingEventRepository.save(event);
                return saved.getEventId();
        }
}
