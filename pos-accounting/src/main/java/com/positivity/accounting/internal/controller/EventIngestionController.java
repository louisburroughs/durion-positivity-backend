package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.AccountingEventFilter;
import com.positivity.accounting.internal.dto.AccountingEventResponse;
import com.positivity.accounting.internal.dto.AccountingEventSubmitRequest;
import com.positivity.accounting.internal.dto.EventEnvelopeContract;
import com.positivity.accounting.internal.dto.EventProcessingLogEntry;
import com.positivity.accounting.internal.dto.ReprocessEventRequest;
import com.positivity.accounting.internal.dto.ReprocessingAttemptHistoryResponse;
import com.positivity.accounting.internal.enums.AccountingEventStatus;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.service.EventIngestionService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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
    @PreAuthorize("hasAuthority('" + AccountingPermissions.EVENTS_VIEW + "')")
    @Operation(
            operationId = "listAccountingEvents",
            summary = "List Accounting Events",
            description = """
                    Lists ingested accounting events as a paginated projection with rich optional filters: \
                    organization, event type, idempotency outcome, received-at range, event id, ingestion \
                    id, domain key, invoice id and processing status.
                    Use this tool to monitor or triage the event pipeline; do not use getAccountingEvent, \
                    which fetches one event by its known id.
                    Preconditions: none beyond the caller holding accounting:events:view; an unrecognized \
                    status value is silently ignored rather than rejected.
                    Required inputs: none; all filters are optional and the page defaults to 20 items sorted \
                    by receivedAt descending.
                    Emits an ACCOUNTING_EVENT_LIST audit event; no state changes.
                    Returns 200 with an empty page when nothing matches the filters.
                    """,
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
    @PreAuthorize("hasAuthority('" + AccountingPermissions.EVENTS_VIEW + "')")
    @Operation(
            operationId = "getAccountingEvent",
            summary = "Get Accounting Event",
            description = """
                    Returns one ingested accounting event with its payload, processing status and idempotency \
                    outcome.
                    Use this tool when the event id is already known; use listAccountingEvents instead when \
                    searching by type, status or time range.
                    Preconditions: the event must exist.
                    Required inputs: eventId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 EVENT_NOT_FOUND when no accounting event exists for the supplied id.
                    """,
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
    @PreAuthorize("hasAuthority('" + AccountingPermissions.EVENTS_SUBMIT + "')")
    @Operation(
            operationId = "submitAccountingEvent",
            summary = "Submit Accounting Event",
            description = """
                    Submits a business event into the accounting pipeline, where the posting engine converts \
                    it into journal entries via published posting rules or default GL mappings.
                    Use this tool to feed source-system activity into the ledger; do not use \
                    createJournalEntry, which bypasses the rules engine for manual entries, and use \
                    resolveTestMapping to preview the rules first.
                    Preconditions: no event with the same eventId may already be ingested; duplicates are \
                    rejected rather than reprocessed.
                    Required inputs: eventType (max 100 chars), organizationId (UUID) and payload (JSON \
                    object); eventId, sourceSystem and transactionDate (ISO-8601) are optional, eventId being \
                    generated when omitted.
                    Emits an ACCOUNTING_EVENT_SUBMIT event and returns 202 while processing continues \
                    asynchronously; callers poll getAccountingEvent for the outcome.
                    Returns 409 DUPLICATE_EVENT when the eventId was already ingested, and 400 when required \
                    fields are missing or the transactionDate is not valid ISO-8601.
                    """,
            tags = {"Accounting Events"})
    @ApiResponse(responseCode = "202", description = "Event accepted for processing")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @EmitEvent(id = "ACCOUNTING_EVENT_SUBMIT", apiVersion = "1")
    public ResponseEntity<AccountingEventResponse> submitEvent(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Business event envelope to convert into journal entries.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Invoice finalized event", value = """
                                                                    {"eventId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "eventType":"INVOICE_FINALIZED",
                                                                     "organizationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
                                                                     "sourceSystem":"POS",
                                                                     "transactionDate":"2026-08-13T10:15:00",
                                                                     "payload":{"invoiceId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5d","totalAmount":150.00}}
                                                                    """)))
                    @Valid
                    @RequestBody
                    AccountingEventSubmitRequest request) {

        AccountingEventResponse response = eventIngestionService.submitEvent(request.toMap());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @PostMapping("/{eventId}/retry")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:events:retry"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.EVENTS_RETRY + "')")
    @Operation(
            operationId = "retryAccountingEvent",
            summary = "Retry Accounting Event Processing",
            description = """
                    Re-runs pipeline processing for a failed accounting event using its original payload and \
                    the current rules.
                    Use this tool for transient failures; do not use reprocessSuspendedEvent, which is the \
                    audited path for SUSPENDED events after a mapping or rule correction.
                    Preconditions: the event must exist and be in a retryable failed state.
                    Required inputs: eventId (UUID) as a path parameter; the request body is optional and \
                    ignored.
                    Emits an ACCOUNTING_EVENT_RETRY event and returns 202 while processing continues \
                    asynchronously.
                    Returns 404 EVENT_NOT_FOUND when the event does not exist.
                    """,
            tags = {"Accounting Events"})
    @ApiResponse(responseCode = "202", description = "Retry requested")
    @ApiResponse(responseCode = "404", description = "Event not found")
    @EmitEvent(id = "ACCOUNTING_EVENT_RETRY", apiVersion = "1")
    public ResponseEntity<AccountingEventResponse> retryEventProcessing(
            @Parameter(description = "Event identifier") @PathVariable UUID eventId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Optional and ignored; send an empty object or omit the body entirely.",
                            required = false,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Empty body", value = "{}")))
                    @RequestBody(required = false)
                    Object request) {
        log.info("Retrying event processing: {}", eventId);
        AccountingEventResponse response = eventIngestionService.retryEvent(eventId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @PostMapping("/{eventId}/reprocess")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:events:reprocess"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.EVENTS_REPROCESS + "')")
    @Operation(
            operationId = "reprocessSuspendedEvent",
            summary = "Reprocess Suspended Event",
            description = """
                    Reprocesses a SUSPENDED accounting event after a mapping or rule correction, recording an \
                    audited reprocessing attempt with the triggering user.
                    Use this tool once the underlying mapping gap is fixed; do not use retryAccountingEvent, \
                    which is the unaudited retry for transient failures.
                    Preconditions: the event must exist and be SUSPENDED; an event already PROCESSED is \
                    rejected to preserve idempotency.
                    Required inputs: eventId (UUID) as a path parameter and triggeredByUserId in the body; \
                    mappingVersionToUse and reprocessingNotes are optional.
                    Emits an ACCOUNTING_EVENT_REPROCESS event; a successful synchronous outcome returns 200 \
                    with status PROCESSED while 202 means processing continues.
                    Returns 404 EVENT_NOT_FOUND when the event does not exist, 409 when it is already \
                    PROCESSED, and 400 when the request is invalid.
                    """,
            tags = {"Accounting Events"})
    @ApiResponse(responseCode = "202", description = "Reprocessing accepted")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @ApiResponse(responseCode = "404", description = "Event not found")
    @ApiResponse(responseCode = "409", description = "Event already PROCESSED (idempotency violation)")
    @EmitEvent(id = "ACCOUNTING_EVENT_REPROCESS", apiVersion = "1")
    public ResponseEntity<AccountingEventResponse> reprocessSuspendedEvent(
            @Parameter(description = "Event identifier") @PathVariable UUID eventId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Audited reprocessing trigger with optional mapping version pin and notes.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(name = "Reprocess after mapping fix", value = """
                                                                    {"triggeredByUserId":"jdoe",
                                                                     "reprocessingNotes":"Default mapping added for CASH_SALE"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    ReprocessEventRequest request) {
        AccountingEventResponse response = eventIngestionService.reprocessEvent(eventId, request);
        HttpStatus status =
                AccountingEventStatus.PROCESSED.equals(response.getStatus()) ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/{eventId}/reprocessing-history")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:events:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.EVENTS_VIEW + "')")
    @Operation(
            operationId = "getEventReprocessingHistory",
            summary = "Get Event Reprocessing History",
            description = """
                    Returns every recorded reprocessing attempt for an accounting event, including who \
                    triggered each attempt and its outcome.
                    Use this tool when auditing a suspended event's correction history; use \
                    getEventProcessingLog instead for the step-by-step pipeline log of a single run.
                    Preconditions: none; an unknown event yields an empty list rather than an error.
                    Required inputs: eventId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with an empty list when the event does not exist or was never reprocessed.
                    """,
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
    @PreAuthorize("hasAuthority('" + AccountingPermissions.EVENTS_VIEW + "')")
    @Operation(
            operationId = "getEventProcessingLog",
            summary = "Get Event Processing Log",
            description = """
                    Returns the structured, step-by-step processing audit log for one accounting event, \
                    covering rule matching, mapping resolution and posting outcomes.
                    Use this tool to diagnose why an event suspended or failed; use \
                    getEventReprocessingHistory instead for the list of manual reprocessing attempts.
                    Preconditions: none; an event with no log yields an empty list.
                    Required inputs: eventId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with an empty list when the event has no processing log.
                    """,
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
    @PreAuthorize("hasAuthority('" + AccountingPermissions.EVENTS_VIEW + "')")
    @Operation(
            operationId = "getEventContract",
            summary = "Get Event Envelope Contract",
            description = """
                    Returns the current accounting event envelope schema contract, which SDKs use to \
                    validate events before submission.
                    Use this tool to fetch the authoritative envelope shape before calling \
                    submitAccountingEvent; do not use submitAccountingEvent itself to probe validation \
                    rules.
                    Preconditions: none.
                    Required inputs: none; there are no parameters and no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with the contract document.
                    """,
            tags = {"Accounting Events"})
    @ApiResponse(responseCode = "200", description = "Contract returned")
    @ApiResponse(responseCode = "403", description = "Forbidden")
    public ResponseEntity<EventEnvelopeContract> getEventContract() {
        EventEnvelopeContract contract = eventIngestionService.getEventContract();
        return ResponseEntity.ok(contract);
    }
}
