package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.dto.JournalEntryPostRequest;
import com.positivity.accounting.internal.dto.JournalEntryResponse;
import com.positivity.accounting.internal.dto.JournalEntryReversalRequest;
import com.positivity.accounting.internal.dto.JournalEntryTraceabilityResponse;
import com.positivity.accounting.internal.dto.PagedResponse;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.JournalEntryService;
import com.positivity.accounting.internal.service.SortParamParser;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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
    private static final String UPDATED_AT = "updatedAt";

    private static final Logger log = LoggerFactory.getLogger(JournalEntryController.class);

    /**
     * API sort field → entity property. The API exposes {@code modifiedAt}
     * (see JournalEntryResponse) while the entity property is {@code updatedAt}.
     */
    private static final Map<String, String> SORTABLE_PROPERTIES = Map.of(
            "createdAt",
            "createdAt",
            "modifiedAt",
            UPDATED_AT,
            UPDATED_AT,
            UPDATED_AT,
            "transactionDate",
            "transactionDate",
            "postedAt",
            "postedAt",
            "status",
            "status",
            "entryType",
            "entryType",
            "description",
            "description",
            "createdBy",
            "createdBy",
            "journalEntryId",
            "journalEntryId");

    private final JournalEntryService journalEntryService;

    public JournalEntryController(@NonNull JournalEntryService journalEntryService) {
        this.journalEntryService = journalEntryService;
    }

    @GetMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:je:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.JE_VIEW + "')")
    @Operation(
            operationId = "listJournalEntries",
            summary = "List Journal Entries",
            description = """
                    Lists journal entries of every status as a paginated projection, optionally filtered by \
                    the exact posted-entry number.
                    Use this tool when browsing or searching entries; do not use getJournalEntry, which \
                    retrieves one entry by its known id.
                    Preconditions: none beyond the caller holding accounting:je:view.
                    Required inputs: none; page defaults to 0, size to 20, sort to createdAt descending, and \
                    entryNumber (format JE-{YYYYMM}-{seq}, assigned only at posting) is an optional exact-match \
                    filter that never matches unposted entries.
                    Emits an ACCOUNTING_JOURNAL_ENTRY_LIST audit event; no accounting state changes.
                    Returns 400 when the sort property is not one of the supported fields.
                    """,
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
                entryPage.getContent(), entryPage.getNumber(), entryPage.getSize(), entryPage.getTotalElements());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{journalEntryId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:je:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.JE_VIEW + "')")
    @Operation(
            operationId = "getJournalEntry",
            summary = "Get Journal Entry",
            description = """
                    Returns one journal entry with its lines, status (DRAFT, POSTED or REVERSED), entry type \
                    and posting metadata.
                    Use this tool when the journal entry id is already known; use listJournalEntries instead \
                    when searching by entry number or browsing.
                    Preconditions: the journal entry must exist.
                    Required inputs: journalEntryId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 400 with code VALIDATION_ERROR when no journal entry exists for the supplied id \
                    (this module maps entry not-found to 400, not 404).
                    """,
            tags = {"Journal Entries"})
    @ApiResponse(responseCode = "200", description = "Journal entry returned")
    @ApiResponse(responseCode = "400", description = "No journal entry exists for the identifier (VALIDATION_ERROR)")
    public ResponseEntity<JournalEntryResponse> getJournalEntry(
            @Parameter(description = "Journal entry identifier") @PathVariable UUID journalEntryId) {
        log.debug("Getting journal entry: {}", journalEntryId);
        var entry = journalEntryService.getJournalEntry(journalEntryId);
        return ResponseEntity.ok(entry);
    }

    @GetMapping("/{journalEntryId}/traceability")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:je:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.JE_VIEW + "')")
    @Operation(
            operationId = "getJournalEntryTraceability",
            summary = "Get Journal Entry Traceability",
            description = """
                    Returns the traceability chain for a journal entry: its source event, posting rule set and \
                    version, and any reversal relationships.
                    Use this tool when auditing where an entry came from; use getJournalEntry instead for the \
                    entry's lines and amounts.
                    Preconditions: the journal entry must exist; system-generated entries carry source-event \
                    links while manual entries may not.
                    Required inputs: journalEntryId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 400 with code VALIDATION_ERROR when no journal entry exists for the supplied id.
                    """,
            tags = {"Journal Entries"})
    @ApiResponse(responseCode = "200", description = "Journal traceability returned")
    @ApiResponse(responseCode = "400", description = "No journal entry exists for the identifier (VALIDATION_ERROR)")
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
    @PreAuthorize("hasAuthority('" + AccountingPermissions.JE_CREATE + "')")
    @Operation(
            operationId = "createJournalEntry",
            summary = "Create Journal Entry",
            description = """
                    Creates a balanced journal entry in DRAFT status; nothing hits the general ledger until \
                    the entry is posted.
                    Use this tool to stage a manual entry for review; do not use postJournalEntry, which \
                    finalizes an existing draft, and note that system event-driven entries are created by the \
                    posting engine, not this operation.
                    Preconditions: total debits must equal total credits across the lines, and every \
                    glAccountId must reference a GL account active on the transaction date.
                    Required inputs: transactionDate and at least one line with glAccountId (UUID) and a \
                    debitAmount or creditAmount; description (max 500), sourceEventId, sourceEventType and \
                    dimensions are optional.
                    Emits an ACCOUNTING_JOURNAL_ENTRY_CREATE event; GL balances are unchanged until posting.
                    Returns 400 for a malformed request, 404 GL_ACCOUNT_NOT_FOUND when a line's glAccountId \
                    does not exist, and 422 UNBALANCED_ENTRY or GL_ACCOUNT_NOT_ACTIVE when the entry is \
                    unbalanced or a GL account is not active on the transaction date.
                    """,
            tags = {"Journal Entries"})
    @ApiResponse(responseCode = "201", description = "Journal entry created")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @ApiResponse(responseCode = "404", description = "GL account not found (GL_ACCOUNT_NOT_FOUND)")
    @ApiResponse(
            responseCode = "422",
            description = "Entry is unbalanced (UNBALANCED_ENTRY), or a GL account is not active on the"
                    + " transaction date (GL_ACCOUNT_NOT_ACTIVE)")
    @EmitEvent(id = "ACCOUNTING_JOURNAL_ENTRY_CREATE", apiVersion = "1")
    public ResponseEntity<JournalEntryResponse> createJournalEntry(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Draft journal entry with balanced debit and credit lines.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Balanced two-line entry", value = """
                                                                    {"transactionDate":"2026-08-13T00:00:00",
                                                                     "description":"Record cash sale",
                                                                     "lines":[
                                                                       {"glAccountId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                        "debitAmount":125.00,
                                                                        "description":"Cash"},
                                                                       {"glAccountId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
                                                                        "creditAmount":125.00,
                                                                        "description":"Sales revenue"}]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    JournalEntryCreateRequest request) {
        log.debug("Creating journal entry: {}", request.getDescription());
        var created = journalEntryService.createJournalEntry(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{journalEntryId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:je:create"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.JE_CREATE + "')")
    @Operation(
            operationId = "updateJournalEntry",
            summary = "Update Journal Entry",
            description = """
                    Replaces the description and lines of a journal entry that is still in DRAFT status; \
                    posted entries are immutable.
                    Use this tool to correct a draft before posting; do not use it on a POSTED entry, which \
                    only reverseJournalEntry can back out.
                    Preconditions: the entry must exist in DRAFT status, and the replacement lines must \
                    remain balanced.
                    Required inputs: journalEntryId (UUID) as a path parameter plus the full replacement body \
                    (same shape as createJournalEntry); supplied lines replace the existing line set wholesale.
                    Emits an ACCOUNTING_JOURNAL_ENTRY_UPDATE event; GL balances are unchanged because drafts \
                    are not yet in the ledger.
                    Returns 400 VALIDATION_ERROR when the entry id does not exist (this module maps entry \
                    not-found to 400, not 404), 409 when the entry is no longer DRAFT, and 422 \
                    UNBALANCED_ENTRY when the updated entry is unbalanced.
                    """,
            tags = {"Journal Entries"})
    @ApiResponse(responseCode = "200", description = "Journal entry updated")
    @ApiResponse(responseCode = "400", description = "No journal entry exists for the identifier (VALIDATION_ERROR)")
    @ApiResponse(responseCode = "409", description = "Entry is no longer DRAFT")
    @ApiResponse(responseCode = "422", description = "Updated entry is unbalanced (UNBALANCED_ENTRY)")
    @EmitEvent(id = "ACCOUNTING_JOURNAL_ENTRY_UPDATE", apiVersion = "1")
    public ResponseEntity<JournalEntryResponse> updateJournalEntry(
            @Parameter(description = "Journal entry identifier") @PathVariable UUID journalEntryId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Replacement description and balanced line set for the draft entry.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Corrected draft", value = """
                                                                    {"transactionDate":"2026-08-13T00:00:00",
                                                                     "description":"Corrected cash sale amount",
                                                                     "lines":[
                                                                       {"glAccountId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                        "debitAmount":130.00},
                                                                       {"glAccountId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
                                                                        "creditAmount":130.00}]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    JournalEntryCreateRequest request) {
        log.debug("Updating journal entry: {}", journalEntryId);
        var updated = journalEntryService.updateJournalEntry(journalEntryId, request);
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/{journalEntryId}/post")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:je:post"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.JE_POST + "')")
    @Operation(
            operationId = "postJournalEntry",
            summary = "Post Journal Entry",
            description = """
                    Posts a DRAFT journal entry to the general ledger (DRAFT to POSTED), assigning its \
                    entryNumber (JE-{YYYYMM}-{seq}) and updating GL balances; the entry is immutable \
                    afterwards.
                    Use this tool to finalize a balanced draft; do not use reverseJournalEntry, which backs \
                    out an entry that is already POSTED.
                    Preconditions: the entry must exist in DRAFT status, debits must equal credits, every \
                    line's GL account must be active on the transaction date, and the transaction date must \
                    clear the accounting-period gate.
                    Required inputs: journalEntryId (UUID) as a path parameter; the body is optional and \
                    carries only overrideJustification (max 500 chars), which together with the \
                    accounting:period:override permission allows posting into a CLOSED period with the \
                    override audit-logged; a date before the org hard-lock date is never overridable.
                    Emits an ACCOUNTING_JOURNAL_ENTRY_POST event and returns the posted entry.
                    Returns 400 VALIDATION_ERROR when no entry exists for the id (this module maps entry \
                    not-found to 400, not 404) or overrideJustification exceeds 500 characters, 409 \
                    ENTRY_ALREADY_POSTED when the entry is already POSTED or REVERSED, and 422 \
                    UNBALANCED_ENTRY, GL_ACCOUNT_NOT_ACTIVE, PERIOD_CLOSED or PERIOD_HARD_LOCKED for \
                    domain-policy or period-gate failures.
                    """,
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
                    + " overridable), the entry is unbalanced (UNBALANCED_ENTRY), or a line's GL account is"
                    + " not active on the transaction date (GL_ACCOUNT_NOT_ACTIVE)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_JOURNAL_ENTRY_POST", apiVersion = "1")
    public ResponseEntity<JournalEntryResponse> postJournalEntry(
            @Parameter(description = "Journal entry identifier") @PathVariable UUID journalEntryId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Optional closed-period override justification; omit the body for a normal post.",
                            required = false,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Closed-period override",
                                                            value =
                                                                    "{\"overrideJustification\":\"Late vendor accrual approved by controller\"}")))
                    @Valid
                    @RequestBody(required = false)
                    JournalEntryPostRequest request) {
        log.info("Posting journal entry: {}", journalEntryId);
        String overrideJustification = request != null ? request.getOverrideJustification() : null;
        var posted = journalEntryService.postJournalEntry(journalEntryId, overrideJustification);
        return ResponseEntity.ok(posted);
    }

    @PostMapping("/{journalEntryId}/reverse")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:je:reverse"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.JE_REVERSE + "')")
    @Operation(
            operationId = "reverseJournalEntry",
            summary = "Reverse Journal Entry",
            description = """
                    Reverses a POSTED journal entry by creating and immediately posting an inverse entry \
                    (debits and credits swapped) with its own entryNumber, transitioning the original POSTED \
                    to REVERSED.
                    Use this tool to back out an incorrect posted entry; do not use it on DRAFT entries, \
                    which updateJournalEntry can still edit, and do not use postJournalEntry, which finalizes \
                    drafts.
                    Preconditions: the entry must exist in POSTED status, and the resolved reversal date must \
                    clear the accounting-period gate.
                    Required inputs: a non-blank reason (recorded on the reversal entry and audit trail); \
                    reversalDate is optional and defaults to the original transaction date when that period is \
                    OPEN, otherwise today; overrideJustification with the accounting:period:override \
                    permission allows reversing into a CLOSED period, but never before the hard-lock date.
                    Emits an ACCOUNTING_JOURNAL_ENTRY_REVERSE event and returns the posted reversal entry.
                    Returns 409 JE_ALREADY_REVERSED when the entry was already reversed (including a lost \
                    concurrent-reversal race), 409 JE_NOT_POSTED when it is DRAFT or PENDING, 422 \
                    PERIOD_CLOSED or PERIOD_HARD_LOCKED for period-gate failures, and 400 when the reason is \
                    blank or the entry does not exist.
                    """,
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
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Reversal reason with optional reversal date and closed-period override justification.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(name = "Reverse with explicit date", value = """
                                                                    {"reason":"Duplicate posting of invoice INV-1042",
                                                                     "reversalDate":"2026-08-13"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    JournalEntryReversalRequest request) {
        log.info("Reversing journal entry: {}", journalEntryId);
        var reversed = journalEntryService.reverseJournalEntry(
                journalEntryId, request.getReason(), request.getReversalDate(), request.getOverrideJustification());
        return ResponseEntity.ok(reversed);
    }
}
