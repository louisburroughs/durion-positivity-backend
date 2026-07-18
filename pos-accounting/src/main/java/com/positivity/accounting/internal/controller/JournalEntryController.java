package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.dto.JournalEntryMapper;
import com.positivity.accounting.internal.dto.JournalEntryPostRequest;
import com.positivity.accounting.internal.dto.JournalEntryResponse;
import com.positivity.accounting.internal.dto.JournalEntryReversalRequest;
import com.positivity.accounting.internal.dto.JournalEntryTraceabilityResponse;
import com.positivity.accounting.internal.dto.PagedResponse;
import com.positivity.accounting.internal.service.SortParamParser;
import com.positivity.accounting.service.JournalEntryService;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller for Journal Entry operations.
 * Handles creation, viewing, posting, and reversal of journal entries.
 */
@RestController
@RequestMapping("/v1/accounting/journal-entries")
@Tag(name = "Journal Entries", description = "Manage journal entries including posting and reversal.")
@Validated
public class JournalEntryController {

    private static final Logger log = LoggerFactory.getLogger(JournalEntryController.class);

    /**
     * API sort field → entity property. The API exposes {@code modifiedAt}
     * (see JournalEntryResponse) while the entity property is {@code updatedAt}.
     */
    private static final Map<String, String> SORTABLE_PROPERTIES = Map.of(
            "createdAt", "createdAt",
            "modifiedAt", "updatedAt",
            "updatedAt", "updatedAt",
            "transactionDate", "transactionDate",
            "postedAt", "postedAt",
            "status", "status",
            "entryType", "entryType",
            "description", "description",
            "createdBy", "createdBy",
            "journalEntryId", "journalEntryId");

    private final JournalEntryService journalEntryService;

    public JournalEntryController(@NonNull JournalEntryService journalEntryService) {
        this.journalEntryService = journalEntryService;
    }

    @GetMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:je:view"})
    @PreAuthorize("hasAuthority('accounting:je:view')")
    @Operation(
            summary = "List journal entries",
            description = "Retrieve paginated journal entries, optionally filtered by exact posted-entry number.",
            tags = {"Journal Entries"})
    @ApiResponse(responseCode = "200", description = "Journal entries listed")
    @ApiResponse(responseCode = "400", description = "Unsupported sort property or direction")
    @ApiResponse(responseCode = "403", description = "Forbidden")
    @EmitEvent(id = "ACCOUNTING_JOURNAL_ENTRY_LIST", apiVersion = "1")
    public ResponseEntity<PagedResponse<JournalEntryResponse>> listJournalEntries(
            @Parameter(description = "Page index (0-based)") @PositiveOrZero @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @Positive @RequestParam(defaultValue = "20") int size,
            @Parameter(
                            description = "Sort field with optional direction, e.g. 'modifiedAt,desc'. "
                                    + "Supported fields: createdAt, modifiedAt, updatedAt, transactionDate, "
                                    + "postedAt, status, entryType, description, createdBy, journalEntryId. "
                                    + "Direction defaults to desc.")
                    @NotBlank
                    @RequestParam(defaultValue = "createdAt")
                    String sort,
            @Parameter(
                            description = "Optional exact-match filter on the posted-entry number "
                                    + "(format JE-{YYYYMM}-{seq}, sequential within the entry's transaction "
                                    + "month, assigned at posting time). Entries not yet posted have no "
                                    + "entry number and never match.",
                            example = "JE-202607-1")
                    @RequestParam(required = false)
                    String entryNumber) {
        log.debug("Listing journal entries: page={}, size={}, entryNumber={}", page, size, entryNumber);

        Pageable pageable =
                PageRequest.of(page, size, SortParamParser.parse(sort, SORTABLE_PROPERTIES, Sort.Direction.DESC));
        var entryPage = journalEntryService.listJournalEntries(pageable, entryNumber);

        PagedResponse<JournalEntryResponse> response = new PagedResponse<>(
                entryPage.getContent().stream()
                        .map(JournalEntryMapper::toResponse)
                        .toList(),
                entryPage.getNumber(),
                entryPage.getSize(),
                entryPage.getTotalElements());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{journalEntryId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:je:view"})
    @PreAuthorize("hasAuthority('accounting:je:view')")
    @Operation(
            summary = "Get journal entry",
            description = "Retrieve a journal entry by identifier.",
            tags = {"Journal Entries"})
    @ApiResponse(responseCode = "200", description = "Journal entry returned")
    @ApiResponse(responseCode = "404", description = "Journal entry not found")
    public ResponseEntity<JournalEntryResponse> getJournalEntry(
            @Parameter(description = "Journal entry identifier") @PathVariable UUID journalEntryId) {
        log.debug("Getting journal entry: {}", journalEntryId);
        var entry = journalEntryService.getJournalEntry(journalEntryId);
        return ResponseEntity.ok(JournalEntryMapper.toResponse(entry));
    }

    @GetMapping("/{journalEntryId}/traceability")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:je:view"})
    @PreAuthorize("hasAuthority('accounting:je:view')")
    @Operation(
            summary = "Get journal traceability",
            description = "Trace a journal entry across related records.",
            tags = {"Journal Entries"})
    @ApiResponse(responseCode = "200", description = "Journal traceability returned")
    @ApiResponse(responseCode = "404", description = "Journal entry not found")
    public ResponseEntity<JournalEntryTraceabilityResponse> getJournalTraceability(
            @Parameter(description = "Journal entry identifier") @PathVariable UUID journalEntryId) {
        log.debug("Getting journal traceability: {}", journalEntryId);
        var traceability = journalEntryService.getJournalTraceability(journalEntryId);
        return ResponseEntity.ok(traceability);
    }

    @PostMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:je:create"})
    @PreAuthorize("hasAuthority('accounting:je:create')")
    @Operation(
            summary = "Create journal entry",
            description = "Create a new journal entry.",
            tags = {"Journal Entries"})
    @ApiResponse(responseCode = "201", description = "Journal entry created")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @EmitEvent(id = "ACCOUNTING_JOURNAL_ENTRY_CREATE", apiVersion = "1")
    public ResponseEntity<JournalEntryResponse> createJournalEntry(
            @Valid @RequestBody JournalEntryCreateRequest request) {
        log.debug("Creating journal entry: {}", request.getDescription());
        var entity = JournalEntryMapper.toEntity(request);
        var created = journalEntryService.createJournalEntry(entity);
        return ResponseEntity.status(HttpStatus.CREATED).body(JournalEntryMapper.toResponse(created));
    }

    @PutMapping("/{journalEntryId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:je:create"})
    @PreAuthorize("hasAuthority('accounting:je:create')")
    @Operation(
            summary = "Update journal entry",
            description = "Update an existing journal entry.",
            tags = {"Journal Entries"})
    @ApiResponse(responseCode = "200", description = "Journal entry updated")
    @ApiResponse(responseCode = "404", description = "Journal entry not found")
    @EmitEvent(id = "ACCOUNTING_JOURNAL_ENTRY_UPDATE", apiVersion = "1")
    public ResponseEntity<JournalEntryResponse> updateJournalEntry(
            @Parameter(description = "Journal entry identifier") @PathVariable UUID journalEntryId,
            @Valid @RequestBody JournalEntryCreateRequest request) {
        log.debug("Updating journal entry: {}", journalEntryId);
        var updates = JournalEntryMapper.toEntity(request);
        var updated = journalEntryService.updateJournalEntry(journalEntryId, updates);
        return ResponseEntity.ok(JournalEntryMapper.toResponse(updated));
    }

    @PostMapping("/{journalEntryId}/post")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:je:post"})
    @PreAuthorize("hasAuthority('accounting:je:post')")
    @Operation(
            summary = "Post journal entry",
            operationId = "postJournalEntry",
            description = "Posts a DRAFT journal entry to the general ledger (DRAFT → POSTED), assigning its"
                    + " entryNumber and updating GL balances; the entry is immutable afterwards — use"
                    + " reverseJournalEntry to back it out."
                    + " Preconditions: the entry must exist, be in DRAFT status, and be balanced."
                    + " The request body is OPTIONAL: omit it entirely for a normal post. The entry's"
                    + " transaction date is checked against the accounting-period gate (story B2): a date"
                    + " strictly before the org-level hard-lock date is rejected unconditionally with 422"
                    + " PERIOD_HARD_LOCKED (never overridable); a date in a CLOSED period is rejected with 422"
                    + " PERIOD_CLOSED unless the caller holds accounting:period:override AND supplies a"
                    + " non-blank overrideJustification in the body, in which case the posting proceeds and"
                    + " the override is audit-logged."
                    + " Emits ACCOUNTING_JOURNAL_ENTRY_POST and returns the posted entry."
                    + " Returns 409 ENTRY_ALREADY_POSTED if the entry is already POSTED or REVERSED, and 422"
                    + " UNBALANCED_ENTRY if debits do not equal credits.",
            tags = {"Journal Entries"})
    @ApiResponse(
            responseCode = "200",
            description = "Journal entry posted; the posted entry with its entryNumber is returned",
            content = @Content(schema = @Schema(implementation = JournalEntryResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "No journal entry exists for the identifier (VALIDATION_ERROR), or the"
                    + " overrideJustification exceeds 500 characters (ARGUMENT_NOT_VALID)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks the accounting:je:post permission",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Entry is already posted or reversed (ENTRY_ALREADY_POSTED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Transaction date is in a CLOSED period without a valid override (PERIOD_CLOSED —"
                    + " accounting:period:override plus a non-blank overrideJustification allows posting into"
                    + " closed periods), is strictly before the hard-lock date (PERIOD_HARD_LOCKED — never"
                    + " overridable), or the entry is unbalanced (UNBALANCED_ENTRY)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_JOURNAL_ENTRY_POST", apiVersion = "1")
    public ResponseEntity<JournalEntryResponse> postJournalEntry(
            @Parameter(description = "Journal entry identifier") @PathVariable UUID journalEntryId,
            @Valid @RequestBody(required = false) JournalEntryPostRequest request) {
        log.info("Posting journal entry: {}", journalEntryId);
        String overrideJustification = request != null ? request.getOverrideJustification() : null;
        var posted = journalEntryService.postJournalEntry(journalEntryId, overrideJustification);
        return ResponseEntity.ok(JournalEntryMapper.toResponse(posted));
    }

    @PostMapping("/{journalEntryId}/reverse")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:je:reverse"})
    @PreAuthorize("hasAuthority('accounting:je:reverse')")
    @Operation(
            summary = "Reverse journal entry",
            operationId = "reverseJournalEntry",
            description = "Reverses a POSTED journal entry by creating and immediately posting an inverse entry"
                    + " (debits and credits swapped) with its own entryNumber, and transitioning the original"
                    + " POSTED → REVERSED. Use this tool to back out an incorrect posted entry; do NOT use it"
                    + " on DRAFT entries — delete or edit those instead."
                    + " Preconditions: the entry must exist and be in POSTED status."
                    + " Required input: a non-blank reason, recorded on the reversal entry and in the audit"
                    + " trail. Optional input: reversalDate — when omitted, it defaults to the original entry's"
                    + " transaction date if that period is OPEN, otherwise to today; the resolved date must fall"
                    + " in an OPEN accounting period."
                    + " Period gate (story B2): a resolved date strictly before the org-level hard-lock date"
                    + " is rejected unconditionally with 422 PERIOD_HARD_LOCKED (never overridable); a date in"
                    + " a CLOSED period is rejected with 422 PERIOD_CLOSED unless the caller holds"
                    + " accounting:period:override AND supplies a non-blank overrideJustification, in which"
                    + " case the reversal posts into the closed period and the override is audit-logged."
                    + " Emits ACCOUNTING_JOURNAL_ENTRY_REVERSE and returns the reversal entry."
                    + " Returns 409 JE_ALREADY_REVERSED if the entry was already reversed (including a lost"
                    + " concurrent-reversal race), 409 JE_NOT_POSTED if it is DRAFT/PENDING, and 422"
                    + " PERIOD_CLOSED if the reversal date falls in a CLOSED period without a valid override —"
                    + " pick an open-period date, supply an override, or reopen the period before retrying.",
            tags = {"Journal Entries"})
    @ApiResponse(
            responseCode = "200",
            description = "Journal entry reversed; the posted reversal entry is returned",
            content = @Content(schema = @Schema(implementation = JournalEntryResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Reason is missing or blank (ARGUMENT_NOT_VALID), or no journal entry exists for the"
                    + " identifier (VALIDATION_ERROR)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks the accounting:je:reverse permission",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Entry is already reversed (JE_ALREADY_REVERSED) or not yet posted (JE_NOT_POSTED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Resolved reversal date falls in a CLOSED accounting period without a valid override"
                    + " (PERIOD_CLOSED — accounting:period:override plus a non-blank overrideJustification"
                    + " allows reversing into closed periods), or is strictly before the hard-lock date"
                    + " (PERIOD_HARD_LOCKED — never overridable)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_JOURNAL_ENTRY_REVERSE", apiVersion = "1")
    public ResponseEntity<JournalEntryResponse> reverseJournalEntry(
            @Parameter(description = "Journal entry identifier") @PathVariable UUID journalEntryId,
            @Valid @RequestBody JournalEntryReversalRequest request) {
        log.info("Reversing journal entry: {}", journalEntryId);
        var reversed = journalEntryService.reverseJournalEntry(
                journalEntryId, request.getReason(), request.getReversalDate(), request.getOverrideJustification());
        return ResponseEntity.ok(JournalEntryMapper.toResponse(reversed));
    }
}
