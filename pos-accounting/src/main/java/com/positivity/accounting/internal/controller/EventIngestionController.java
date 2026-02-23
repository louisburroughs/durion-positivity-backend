package com.positivity.accounting.internal.controller;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.positivity.accounting.internal.dto.AccountingEventResponse;
import com.positivity.accounting.internal.dto.AccountingEventSubmitRequest;
import com.positivity.accounting.internal.dto.PagedResponse;
import com.positivity.accounting.internal.dto.ReprocessEventRequest;
import com.positivity.accounting.internal.dto.ReprocessingAttemptHistoryResponse;
import com.positivity.accounting.service.EventIngestionService;
import com.positivity.events.EmitEvent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * REST Controller for Accounting Event Ingestion.
 * Handles submission, retrieval, and retry of business events that trigger
 * journal entry creation.
 */
@RestController
@RequestMapping("/v1/accounting/events")
@Tag(name = "Accounting Events", description = "Ingest and manage accounting events for journal processing.")
public class EventIngestionController {

        private static final Logger log = LoggerFactory.getLogger(EventIngestionController.class);
        private final EventIngestionService eventIngestionService;

        public EventIngestionController(@NonNull EventIngestionService eventIngestionService) {
                this.eventIngestionService = eventIngestionService;
        }

        @GetMapping
        @PreAuthorize("hasAuthority('accounting:events:view')")
        @Operation(summary = "List events", description = "Retrieve paginated accounting events with optional filters.")
        @ApiResponse(responseCode = "200", description = "Events listed")
        @ApiResponse(responseCode = "403", description = "Forbidden")
        @EmitEvent(id = "ACCOUNTING_EVENT_LIST", apiVersion = "1")
        public ResponseEntity<PagedResponse<AccountingEventResponse>> listEvents(
                        @Parameter(description = "Organization identifier") @RequestParam UUID organizationId,
                        @Parameter(description = "Page index (0-based)") @RequestParam(defaultValue = "0") int page,
                        @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size,
                        @Parameter(description = "Filter by processing status") @RequestParam(required = false) String status) {
                log.debug("Listing accounting events: org={}, page={}, size={}, status={}", organizationId, page, size,
                                status);

                Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "receivedAt"));
                Page<AccountingEventResponse> eventPage = eventIngestionService.listEvents(organizationId, status,
                                pageable);

                PagedResponse<AccountingEventResponse> response = new PagedResponse<>(
                                eventPage.getContent(),
                                eventPage.getNumber(),
                                eventPage.getSize(),
                                eventPage.getTotalElements());

                return ResponseEntity.ok(response);
        }

        @GetMapping("/{eventId}")
        @PreAuthorize("hasAuthority('accounting:events:view')")
        @Operation(summary = "Get event", description = "Retrieve details for an accounting event.")
        @ApiResponse(responseCode = "200", description = "Event returned")
        @ApiResponse(responseCode = "404", description = "Event not found")
        public ResponseEntity<AccountingEventResponse> getEvent(
                        @Parameter(description = "Event identifier") @PathVariable UUID eventId) {
                log.debug("Getting accounting event: {}", eventId);
                AccountingEventResponse response = eventIngestionService.getEventById(eventId);
                return ResponseEntity.ok(response);
        }

        @PostMapping
        @PreAuthorize("hasAuthority('accounting:events:submit')")
        @Operation(summary = "Submit event", description = "Submit a new accounting event for processing.")
        @ApiResponse(responseCode = "202", description = "Event accepted for processing")
        @ApiResponse(responseCode = "400", description = "Invalid request")
        @EmitEvent(id = "ACCOUNTING_EVENT_SUBMIT", apiVersion = "1")
        public ResponseEntity<AccountingEventResponse> submitEvent(
                        @Valid @RequestBody AccountingEventSubmitRequest request) {
                log.info("Submitting accounting event: type={}, org={}", request.getEventType(),
                                request.getOrganizationId());
                AccountingEventResponse response = eventIngestionService.submitEvent(request.toMap());
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        }

        @PostMapping("/{eventId}/retry")
        @PreAuthorize("hasAuthority('accounting:events:retry')")
        @Operation(summary = "Retry event processing", description = "Retry processing for a failed accounting event.")
        @ApiResponse(responseCode = "202", description = "Retry requested")
        @ApiResponse(responseCode = "404", description = "Event not found")
        @EmitEvent(id = "ACCOUNTING_EVENT_RETRY", apiVersion = "1")
        public ResponseEntity<AccountingEventResponse> retryEventProcessing(
                        @Parameter(description = "Event identifier") @PathVariable UUID eventId,
                        @RequestBody(required = false) Object request) {
                log.info("Retrying event processing: {}", eventId);
                AccountingEventResponse response = eventIngestionService.retryEvent(eventId);
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        }

        @PostMapping("/{eventId}/reprocess")
        @PreAuthorize("hasAuthority('accounting:events:reprocess')")
        @Operation(summary = "Reprocess suspended event", description = "Reprocess a SUSPENDED accounting event after mapping/rule correction. Idempotent - returns 409 Conflict if already PROCESSED.")
        @ApiResponse(responseCode = "202", description = "Reprocessing accepted")
        @ApiResponse(responseCode = "400", description = "Invalid request")
        @ApiResponse(responseCode = "404", description = "Event not found")
        @ApiResponse(responseCode = "409", description = "Event already PROCESSED (idempotency violation)")
        @EmitEvent(id = "ACCOUNTING_EVENT_REPROCESS", apiVersion = "1")
        public ResponseEntity<AccountingEventResponse> reprocessSuspendedEvent(
                        @Parameter(description = "Event identifier") @PathVariable UUID eventId,
                        @Valid @RequestBody ReprocessEventRequest request) {
                log.info("Reprocessing suspended event {} triggered by user {}", eventId,
                                request.getTriggeredByUserId());

                try {
                        AccountingEventResponse response = eventIngestionService.reprocessEvent(eventId, request);
                        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
                } catch (IllegalStateException e) {
                        // BR-3: Idempotency violation or invalid state - treat as conflict
                        return ResponseEntity.status(HttpStatus.CONFLICT).build();
                }
        }

        @GetMapping("/{eventId}/reprocessing-history")
        @PreAuthorize("hasAuthority('accounting:events:view')")
        @Operation(summary = "Get reprocessing history", description = "Retrieve all reprocessing attempts for a suspended accounting event. Returns an empty list if the event does not exist or has no reprocessing history.")
        @ApiResponse(responseCode = "200", description = "Reprocessing history returned (may be empty list)")
        public ResponseEntity<List<ReprocessingAttemptHistoryResponse>> getReprocessingHistory(
                        @Parameter(description = "Event identifier") @PathVariable UUID eventId) {
                log.debug("Getting reprocessing history for event: {}", eventId);
                List<ReprocessingAttemptHistoryResponse> history = eventIngestionService
                                .getReprocessingHistory(eventId);
                if (history == null || history.isEmpty()) {
                        log.debug("No reprocessing history found for event: {}", eventId);
                        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
                }
                return ResponseEntity.ok(history);
        }

        @GetMapping("/{eventId}/processing-log")
        @PreAuthorize("hasAuthority('accounting:events:view')")
        @Operation(summary = "Get event processing log", description = "Retrieve the processing log for an accounting event.")
        @ApiResponse(responseCode = "200", description = "Processing log returned")
        @ApiResponse(responseCode = "404", description = "Event not found")
        public ResponseEntity<String> getEventProcessingLog(
                        @Parameter(description = "Event identifier") @PathVariable UUID eventId) {
                log.debug("Getting processing log for event: {}", eventId);
                String processingLog = eventIngestionService.getProcessingLog(eventId);
                return ResponseEntity.ok(processingLog);
        }
}
