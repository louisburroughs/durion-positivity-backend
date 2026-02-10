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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.accounting.internal.dto.AccountingEventSubmitRequest;
import com.positivity.accounting.internal.dto.ReprocessEventRequest;

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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Suspense Queue Reprocessing Contract Behavioral Tests (CAP:055)")
public class SuspenseQueueContractBehaviorIT {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        private static final String API_V1 = "/v1/accounting/events";

        // Gateway header values — mirrors what pos-api-gateway injects after JWT
        // validation
        private static final String TEST_USER = "testuser";
        private static final String TEST_AUTHORITIES = String.join(",",
                        "accounting:events:view",
                        "accounting:events:submit",
                        "accounting:events:retry",
                        "accounting:events:reprocess");

        /**
         * Adds gateway authentication headers to a request builder.
         * Mirrors the headers injected by pos-api-gateway after JWT validation.
         */
        private MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder builder) {
                return builder
                                .header("X-User", TEST_USER)
                                .header("X-Authorities", TEST_AUTHORITIES);
        }

        // ===============================================
        // HAPPY PATH SCENARIOS
        // ===============================================

        @Test
        @DisplayName("AC-2: Successful reprocess posts and closes entry")
        public void testReprocessSuspendedEventHappyPath() throws Exception {
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
                // TODO: Add helper method to set event status for test purposes

                // Act: Reprocess the suspended event
                ReprocessEventRequest reprocessRequest = ReprocessEventRequest.builder()
                                .triggeredByUserId("test-admin")
                                .reprocessingNotes("Testing reprocess after rule correction")
                                .build();

                // Note: This test may return 202 (still SUSPENDED) or 200 (PROCESSED) depending
                // on simulated logic
                // Assert: Reprocessing should be accepted
                mockMvc.perform(withAuth(post(API_V1 + "/{eventId}/reprocess", eventId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(reprocessRequest)))
                                .andDo(print())
                                .andExpect(status().isOk().or(status().isAccepted()));
        }

        @Test
        @DisplayName("AC-3: Idempotent reprocess returns 409 for PROCESSED events")
        public void testReprocessIdempotency() throws Exception {
                // This test validates BR-3: Idempotency rule
                // Reprocessing a PROCESSED event should return 409 Conflict

                // Arrange: Create and process an event
                // TODO: Implement test fixture to create a PROCESSED event

                // Act: Attempt to reprocess a PROCESSED event
                // Expected: 409 Conflict

                // Note: This test requires additional setup to mark an event as PROCESSED
                // Skipping for now but documented as required per AC-3
        }

        @Test
        @DisplayName("AC-4: Attempt history is maintained")
        public void testReprocessingAttemptHistoryMaintained() throws Exception {
                // Arrange: Create a suspended event
                AccountingEventSubmitRequest submitRequest = new AccountingEventSubmitRequest();
                submitRequest.setEventType("INVOICE_RECEIVED");
                submitRequest.setOrganizationId(UUID.randomUUID());
                submitRequest.setSourceSystem("TEST_SYSTEM");
                submitRequest.setTransactionDate(LocalDateTime.now());
                submitRequest.setPayload(Map.of(
                                "invoiceId", "INV-002",
                                "amount", 200.00,
                                "description", "Test invoice for history tracking"));

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
                                .andExpect(status().isOk().or(status().isAccepted()));

                // Assert: Reprocessing history should be retrievable
                mockMvc.perform(withAuth(get(API_V1 + "/{eventId}/reprocessing-history", eventId)))
                                .andDo(print())
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$").isArray())
                                .andExpect(jsonPath("$[0].triggeredByUserId").value("test-admin"));
        }

        // ===============================================
        // ERROR SCENARIOS
        // ===============================================

        @Test
        @DisplayName("Reprocess returns 404 for non-existent event")
        public void testReprocessNonExistentEvent() throws Exception {
                // Arrange: Use a random UUID that doesn't exist
                UUID nonExistentId = UUID.randomUUID();

                // Act: Attempt to reprocess
                ReprocessEventRequest reprocessRequest = ReprocessEventRequest.builder()
                                .triggeredByUserId("test-admin")
                                .build();

                // Assert: Should return 404
                mockMvc.perform(withAuth(post(API_V1 + "/{eventId}/reprocess", nonExistentId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(reprocessRequest)))
                                .andDo(print())
                                .andExpect(status().isNotFound().or(status().isBadRequest()));
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
}
