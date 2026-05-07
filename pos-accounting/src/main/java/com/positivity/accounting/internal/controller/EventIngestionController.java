package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.AccountingEventFilter;
import com.positivity.accounting.internal.dto.AccountingEventResponse;
import com.positivity.accounting.internal.dto.AccountingEventSubmitRequest;
import com.positivity.accounting.internal.dto.EventEnvelopeContract;
import com.positivity.accounting.internal.dto.EventProcessingLogEntry;
import com.positivity.accounting.internal.dto.ReprocessEventRequest;
import com.positivity.accounting.internal.dto.ReprocessingAttemptHistoryResponse;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.service.EventIngestionService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller for Accounting Event Ingestion.
 * Handles submission, retrieval, and retry of business events that trigger
 * journal entry creation.
 */
@RestController
@RequestMapping("/v1/accounting/events")
@Tag(name = "Accounting Events", description = "Ingest and manage accounting events for journal processing.")
@Validated
public class EventIngestionController {

    private static final Logger log = LoggerFactory.getLogger(EventIngestionController.class);
    private final EventIngestionService eventIngestionService;

    public EventIngestionController(@NonNull EventIngestionService eventIngestionService) {
        this.eventIngestionService = eventIngestionService;
    }

    @GetMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:events:view"})
    @PreAuthorize("hasAuthority('accounting:events:view')")
    @Operation(
            summary = "List accounting events",
            operationId = "listAccountingEvents",
            description = "Retrieve paginated accounting events with optional filters.",
            tags = {"Accounting Events"})
    @ApiResponse(responseCode = "200", description = "Events listed")
    @ApiResponse(responseCode = "403", description = "Forbidden")
    @EmitEvent(id = "ACCOUNTING_EVENT_LIST", apiVersion = "1")
    public ResponseEntity<Page<AccountingEventResponse>> listAccountingEvents(
            @Parameter(description = "Filter by organization") @RequestParam(required = false) UUID organizationId,
            @Parameter(description = "Filter by event type") @RequestParam(required = false) String eventType,
            @Parameter(description = "Filter by idempotency outcome") @RequestParam(required = false)
                    String idempotencyOutcome,
            @Parameter(description = "Filter by received-at range start (ISO-8601)")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant receivedAtFrom,
            @Parameter(description = "Filter by received-at range end (ISO-8601)")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant receivedAtTo,
            @Parameter(description = "Filter by event UUID") @RequestParam(required = false) UUID eventId,
            @Parameter(description = "Filter by ingestion job UUID") @RequestParam(required = false) UUID ingestionId,
            @Parameter(description = "Filter by domain key") @RequestParam(required = false) String domainKeyId,
            @Parameter(description = "Filter by invoice UUID") @RequestParam(required = false) UUID invoiceId,
            @Parameter(description = "Filter by processing status") @RequestParam(required = false) String status,
            @PageableDefault(size = 20, sort = "receivedAt", direction = Sort.Direction.DESC) Pageable pageable) {

        AccountingEventStatus parsedStatus = null;
        if (status != null) {
            try {
                parsedStatus = AccountingEventStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException _) {
                parsedStatus = null;
            }
        }

        AccountingEventFilter filter = AccountingEventFilter.builder()
                .organizationId(organizationId)
                .eventType(eventType)
                .idempotencyOutcome(idempotencyOutcome)
                .receivedAtFrom(receivedAtFrom)
                .receivedAtTo(receivedAtTo)
                .eventId(eventId)
                .ingestionId(ingestionId)
                .domainKeyId(domainKeyId)
                .invoiceId(invoiceId)
                .status(parsedStatus)
                .build();

        Page<AccountingEventResponse> page = eventIngestionService.listEvents(filter, pageable);
        return ResponseEntity.ok(page);
    }

    @GetMapping("/{eventId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:events:view"})
    @PreAuthorize("hasAuthority('accounting:events:view')")
    @Operation(
            summary = "Get event",
            description = "Retrieve details for an accounting event.",
            tags = {"Accounting Events"})
    @ApiResponse(responseCode = "200", description = "Event returned")
    @ApiResponse(responseCode = "404", description = "Event not found")
    public ResponseEntity<AccountingEventResponse> getEvent(
            @Parameter(description = "Event identifier") @PathVariable UUID eventId) {
        log.debug("Getting accounting event: {}", eventId);
        AccountingEventResponse response = eventIngestionService.getEventById(eventId);
        return ResponseEntity.ok(response);
    }

    @PostMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:events:submit"})
    @PreAuthorize("hasAuthority('accounting:events:submit')")
    @Operation(
            summary = "Submit event",
            description = "Submit a new accounting event for processing.",
            tags = {"Accounting Events"})
    @ApiResponse(responseCode = "202", description = "Event accepted for processing")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @EmitEvent(id = "ACCOUNTING_EVENT_SUBMIT", apiVersion = "1")
    public ResponseEntity<AccountingEventResponse> submitEvent(
            @Valid @RequestBody AccountingEventSubmitRequest request) {

        AccountingEventResponse response = eventIngestionService.submitEvent(request.toMap());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @PostMapping("/{eventId}/retry")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:events:retry"})
    @PreAuthorize("hasAuthority('accounting:events:retry')")
    @Operation(
            summary = "Retry event processing",
            description = "Retry processing for a failed accounting event.",
            tags = {"Accounting Events"})
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
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:events:reprocess"})
    @PreAuthorize("hasAuthority('accounting:events:reprocess')")
    @Operation(
            summary = "Reprocess suspended event",
            description =
                    "Reprocess a SUSPENDED accounting event after mapping/rule correction. Idempotent - returns 409 Conflict if already PROCESSED.",
            tags = {"Accounting Events"})
    @ApiResponse(responseCode = "202", description = "Reprocessing accepted")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @ApiResponse(responseCode = "404", description = "Event not found")
    @ApiResponse(responseCode = "409", description = "Event already PROCESSED (idempotency violation)")
    @EmitEvent(id = "ACCOUNTING_EVENT_REPROCESS", apiVersion = "1")
    public ResponseEntity<AccountingEventResponse> reprocessSuspendedEvent(
            @Parameter(description = "Event identifier") @PathVariable UUID eventId,
            @Valid @RequestBody ReprocessEventRequest request) {
        AccountingEventResponse response = eventIngestionService.reprocessEvent(eventId, request);
        HttpStatus status =
                AccountingEventStatus.PROCESSED.equals(response.getStatus()) ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/{eventId}/reprocessing-history")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:events:view"})
    @PreAuthorize("hasAuthority('accounting:events:view')")
    @Operation(
            summary = "Get reprocessing history",
            description =
                    "Retrieve all reprocessing attempts for a suspended accounting event. Returns an empty list if the event does not exist or has no reprocessing history.",
            tags = {"Accounting Events"})
    @ApiResponse(responseCode = "200", description = "Reprocessing history returned (may be empty list)")
    public ResponseEntity<List<ReprocessingAttemptHistoryResponse>> getReprocessingHistory(
            @Parameter(description = "Event identifier") @PathVariable UUID eventId) {
        log.debug("Getting reprocessing history for event: {}", eventId);
        List<ReprocessingAttemptHistoryResponse> history = eventIngestionService.getReprocessingHistory(eventId);
        return ResponseEntity.ok(history != null ? history : List.of());
    }

    @GetMapping("/{eventId}/processing-log")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:events:view"})
    @PreAuthorize("hasAuthority('accounting:events:view')")
    @Operation(
            summary = "Get event processing log",
            operationId = "getEventProcessingLog",
            description =
                    "Retrieve the structured processing audit log for an accounting event. Returns an empty list if the event has no processing log.",
            tags = {"Accounting Events"})
    @ApiResponse(responseCode = "200", description = "Processing log returned")
    public ResponseEntity<List<EventProcessingLogEntry>> getEventProcessingLog(
            @Parameter(description = "Event identifier") @PathVariable UUID eventId) {
        List<EventProcessingLogEntry> processingLog = eventIngestionService.getEventProcessingLog(eventId);
        return ResponseEntity.ok(processingLog);
    }

    @GetMapping("/contract")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:events:view"})
    @PreAuthorize("hasAuthority('accounting:events:view')")
    @Operation(
            summary = "Get event envelope contract",
            operationId = "getEventContract",
            description = "Returns the current event envelope schema contract for SDK validation.",
            tags = {"Accounting Events"})
    @ApiResponse(responseCode = "200", description = "Contract returned")
    @ApiResponse(responseCode = "403", description = "Forbidden")
    public ResponseEntity<EventEnvelopeContract> getEventContract() {
        EventEnvelopeContract contract = eventIngestionService.getEventContract();
        return ResponseEntity.ok(contract);
    }
}
