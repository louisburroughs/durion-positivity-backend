package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.PostingRuleSetCreateRequest;
import com.positivity.accounting.internal.dto.PostingRuleSetListResponse;
import com.positivity.accounting.internal.dto.PostingRuleSetResponse;
import com.positivity.accounting.internal.dto.PostingRuleVersionResponse;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.PostingRuleService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * REST Controller for Posting Rule Set operations.
 * Handles creation, publishing, and archival of posting rules that drive
 * event-to-JE conversion.
 */
@RestController
@RequestMapping("/v1/accounting/posting-rules")
@Tag(name = "Posting Rules", description = "Manage posting rule sets and their lifecycle.")
@RequiredArgsConstructor
@Validated
public class PostingRuleController {

    private static final Logger log = LoggerFactory.getLogger(PostingRuleController.class);

    private final PostingRuleService postingRuleService;

    @GetMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:posting_rules:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.POSTING_RULES_VIEW + "')")
    @Operation(
            operationId = "listPostingRuleSets",
            summary = "List Posting Rule Sets",
            description = """
                    Lists posting rule sets, the event-to-journal-entry conversion definitions, as a \
                    paginated projection.
                    Use this tool when browsing rule sets; do not use getPostingRuleSet, which fetches one \
                    set by id, or listPostingRuleVersions, which lists a set's version history.
                    Preconditions: none beyond the caller holding accounting:posting_rules:view.
                    Required inputs: none; page defaults to 0, size to 20, and sort to createdAt descending \
                    (supported fields: createdAt, modifiedAt, updatedAt, name, eventType).
                    Emits an ACCOUNTING_POSTING_RULE_LIST audit event; no state changes.
                    Returns 400 when the sort property is not one of the supported fields.
                    """,
            tags = {"Posting Rules"})
    @ApiResponse(responseCode = "200", description = "Posting rule sets listed")
    @ApiResponse(responseCode = "400", description = "Unsupported sort property or direction")
    @ApiResponse(responseCode = "403", description = "Forbidden")
    @EmitEvent(id = "ACCOUNTING_POSTING_RULE_LIST", apiVersion = "1")
    public ResponseEntity<PostingRuleSetListResponse> listPostingRuleSets(
            @Parameter(description = "Page index (0-based)") @PositiveOrZero @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @Positive @RequestParam(defaultValue = "20") int size,
            @Parameter(
                            description = "Sort field with optional direction, e.g. 'modifiedAt,desc'. "
                                    + "Supported fields: createdAt, modifiedAt, updatedAt, name, eventType. "
                                    + "Direction defaults to desc.")
                    @NotBlank
                    @RequestParam(defaultValue = "createdAt")
                    String sort) {
        log.info("List posting rule sets");
        PostingRuleSetListResponse response = postingRuleService.listRuleSetsAsResponse(page, size, sort);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{postingRuleSetId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:posting_rules:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.POSTING_RULES_VIEW + "')")
    @Operation(
            operationId = "getPostingRuleSet",
            summary = "Get Posting Rule Set",
            description = """
                    Returns one posting rule set with its metadata and current version state.
                    Use this tool when the rule set id is already known; use listPostingRuleSets instead when \
                    searching, or listPostingRuleVersions for the full version history.
                    Preconditions: the posting rule set must exist.
                    Required inputs: postingRuleSetId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 POSTING_RULE_SET_NOT_FOUND when no posting rule set exists for the supplied \
                    id.
                    """,
            tags = {"Posting Rules"})
    @ApiResponse(responseCode = "200", description = "Posting rule set returned")
    @ApiResponse(
            responseCode = "404",
            description = "No posting rule set exists for the identifier (POSTING_RULE_SET_NOT_FOUND)")
    public ResponseEntity<PostingRuleSetResponse> getPostingRuleSet(
            @Parameter(description = "Posting rule set identifier") @PathVariable UUID postingRuleSetId) {
        log.info("Get posting rule set - ruleSetId={}", postingRuleSetId);
        PostingRuleSetResponse response = postingRuleService.getPostingRuleSetAsResponse(postingRuleSetId);
        return ResponseEntity.ok(response);
    }

    @PostMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:posting_rules:create"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.POSTING_RULES_CREATE + "')")
    @Operation(
            operationId = "createPostingRuleSet",
            summary = "Create Posting Rule Set",
            description = """
                    Creates a posting rule set for one event type together with its initial DRAFT version \
                    holding the rules definition.
                    Use this tool to introduce new event-to-journal-entry conversion rules; do not use \
                    publishPostingRuleSet, which activates an existing draft, and do not use \
                    createDefaultMapping, which only sets a fallback account pair.
                    Preconditions: none; drafts may hold work-in-progress definitions because split-group and \
                    predicate-grammar invariants are enforced only at publish time.
                    Required inputs: name (max 100 chars), eventType (max 100 chars), rulesDefinition (JSON \
                    rules document as a string) and createdBy (max 50 chars); description is optional.
                    Emits an ACCOUNTING_POSTING_RULE_CREATE event; the new version starts in DRAFT and does \
                    not affect posting until published.
                    Returns 400 when required fields are missing or blank.
                    """,
            tags = {"Posting Rules"})
    @ApiResponse(responseCode = "201", description = "Posting rule set created")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @EmitEvent(id = "ACCOUNTING_POSTING_RULE_CREATE", apiVersion = "1")
    public ResponseEntity<PostingRuleSetResponse> createPostingRuleSet(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Rule set metadata with the initial DRAFT rules definition document.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Invoice finalized rules", value = """
                                                                    {"name":"Invoice posting rules",
                                                                     "eventType":"INVOICE_FINALIZED",
                                                                     "description":"AR and revenue postings for finalized invoices",
                                                                     "rulesDefinition":"{\\"rules\\":[{\\"debit\\":\\"1100\\",\\"credit\\":\\"4000\\",\\"amountField\\":\\"totalAmount\\"}]}",
                                                                     "createdBy":"jdoe"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    PostingRuleSetCreateRequest request) {

        PostingRuleSetResponse response = postingRuleService.createPostingRuleSetWithVersion(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{postingRuleSetId}/publish")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:posting_rules:publish"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.POSTING_RULES_PUBLISH + "')")
    @Operation(
            operationId = "publishPostingRuleSet",
            summary = "Publish Posting Rule Set",
            description = """
                    Publishes the rule set's DRAFT version (DRAFT to PUBLISHED), making it the live \
                    conversion definition for its event type; any previously PUBLISHED version is archived \
                    atomically in the same call.
                    Use this tool to activate reviewed rules; do not use archivePostingRuleSet, which retires \
                    the live version without a replacement, and use resolveTestMapping to dry-run the rules \
                    first.
                    Preconditions: the set must have a DRAFT version with a non-empty rules definition that \
                    passes split-group and condition-predicate validation.
                    Required inputs: postingRuleSetId (UUID) as a path parameter; there is no request body.
                    Emits an ACCOUNTING_POSTING_RULE_PUBLISH event; subsequent accounting events for the \
                    event type post through the new version.
                    Returns 400 when no DRAFT version exists or its definition is empty, 404 \
                    POSTING_RULE_SET_NOT_FOUND when no posting rule set exists for the supplied id, and 422 \
                    UNBALANCED_RULES listing every offending condition, group or line in fieldErrors when \
                    publish-time validation fails.
                    """,
            tags = {"Posting Rules"})
    @ApiResponse(responseCode = "200", description = "Posting rule set published")
    @ApiResponse(responseCode = "400", description = "No DRAFT version exists to publish")
    @ApiResponse(
            responseCode = "404",
            description = "No posting rule set exists for the identifier (POSTING_RULE_SET_NOT_FOUND)")
    @EmitEvent(id = "ACCOUNTING_POSTING_RULE_PUBLISH", apiVersion = "1")
    public ResponseEntity<PostingRuleVersionResponse> publishPostingRuleSet(
            @Parameter(description = "Posting rule set identifier") @PathVariable UUID postingRuleSetId) {
        log.info("Publish posting rule set - ruleSetId={}", postingRuleSetId);
        PostingRuleVersionResponse response = postingRuleService.publishRuleSet(postingRuleSetId);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{postingRuleSetId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:posting_rules:create"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.POSTING_RULES_CREATE + "')")
    @Operation(
            operationId = "updatePostingRuleSet",
            summary = "Update Posting Rule Set",
            description = """
                    Updates a posting rule set's metadata and draft rules definition while no PUBLISHED \
                    version exists.
                    Use this tool to iterate on a draft before publishing; do not use it once a version is \
                    PUBLISHED, and do not use publishPostingRuleSet, which is the activation step.
                    Preconditions: the rule set must exist and must not have a PUBLISHED version.
                    Required inputs: postingRuleSetId (UUID) as a path parameter plus the same body shape as \
                    createPostingRuleSet (name, eventType, rulesDefinition, createdBy).
                    Emits an ACCOUNTING_POSTING_RULE_UPDATE event.
                    Returns 404 POSTING_RULE_SET_NOT_FOUND when the rule set id cannot be resolved, and 409 \
                    when a PUBLISHED version already exists.
                    """,
            tags = {"Posting Rules"})
    @ApiResponse(responseCode = "200", description = "Posting rule set updated")
    @ApiResponse(
            responseCode = "404",
            description = "No posting rule set exists for the identifier (POSTING_RULE_SET_NOT_FOUND)")
    @ApiResponse(responseCode = "409", description = "Cannot modify published rule set")
    @EmitEvent(id = "ACCOUNTING_POSTING_RULE_UPDATE", apiVersion = "1")
    public ResponseEntity<PostingRuleSetResponse> updatePostingRuleSet(
            @Parameter(description = "Posting rule set identifier") @PathVariable UUID postingRuleSetId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Replacement rule set metadata and draft rules definition.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Revised draft rules", value = """
                                                                    {"name":"Invoice posting rules v2",
                                                                     "eventType":"INVOICE_FINALIZED",
                                                                     "rulesDefinition":"{\\"rules\\":[{\\"debit\\":\\"1100\\",\\"credit\\":\\"4000\\",\\"amountField\\":\\"totalAmount\\"}]}",
                                                                     "createdBy":"jdoe"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    PostingRuleSetCreateRequest request) {

        // Delegate to service layer which handles entity creation and updates
        postingRuleService.updatePostingRuleSetFromRequest(postingRuleSetId, request);
        PostingRuleSetResponse response = postingRuleService.getPostingRuleSetAsResponse(postingRuleSetId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{postingRuleSetId}/archive")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:posting_rules:archive"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.POSTING_RULES_ARCHIVE + "')")
    @Operation(
            operationId = "archivePostingRuleSet",
            summary = "Archive Posting Rule Set",
            description = """
                    Archives the rule set's PUBLISHED version (PUBLISHED to ARCHIVED), taking it out of live \
                    event-to-journal-entry conversion.
                    Use this tool to retire live rules deliberately; do not use publishPostingRuleSet, which \
                    already archives the old version automatically when a replacement draft is published.
                    Preconditions: the rule set must have a PUBLISHED version; after archiving, events of \
                    this type fall back to default GL mappings or fail as UNMAPPED_EVENT_TYPE.
                    Required inputs: postingRuleSetId (UUID) as a path parameter; there is no request body.
                    Emits an ACCOUNTING_POSTING_RULE_ARCHIVE event.
                    Returns 400 when no PUBLISHED version exists to archive, and 404 POSTING_RULE_SET_NOT_FOUND \
                    when no posting rule set exists for the supplied id.
                    """,
            tags = {"Posting Rules"})
    @ApiResponse(responseCode = "200", description = "Posting rule set archived")
    @ApiResponse(responseCode = "400", description = "No PUBLISHED version exists to archive")
    @ApiResponse(
            responseCode = "404",
            description = "No posting rule set exists for the identifier (POSTING_RULE_SET_NOT_FOUND)")
    @EmitEvent(id = "ACCOUNTING_POSTING_RULE_ARCHIVE", apiVersion = "1")
    public ResponseEntity<PostingRuleVersionResponse> archivePostingRuleSet(
            @Parameter(description = "Posting rule set identifier") @PathVariable UUID postingRuleSetId) {
        log.info("Archive posting rule set - ruleSetId={}", postingRuleSetId);
        PostingRuleVersionResponse response = postingRuleService.archiveRuleSet(postingRuleSetId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{postingRuleSetId}/versions")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:posting_rules:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.POSTING_RULES_VIEW + "')")
    @Operation(
            operationId = "listPostingRuleVersions",
            summary = "List Posting Rule Versions",
            description = """
                    Lists the version history of one posting rule set with each version's DRAFT, PUBLISHED or \
                    ARCHIVED state and timestamps.
                    Use this tool to audit how a rule set evolved; use getPostingRuleSet instead for the \
                    set's current metadata only.
                    Preconditions: the posting rule set must exist; a set always has at least its initial \
                    version.
                    Required inputs: postingRuleSetId (UUID) as a path parameter; page defaults to 0 and \
                    size to 10.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with an empty list when the page index is beyond the version history, and \
                    404 POSTING_RULE_SET_NOT_FOUND when no posting rule set exists for the supplied id.
                    """,
            tags = {"Posting Rules"})
    @ApiResponse(responseCode = "200", description = "Posting rule versions listed")
    @ApiResponse(
            responseCode = "404",
            description = "No posting rule set exists for the identifier (POSTING_RULE_SET_NOT_FOUND)")
    public ResponseEntity<List<PostingRuleVersionResponse>> listPostingRuleVersions(
            @Parameter(description = "Posting rule set identifier") @PathVariable UUID postingRuleSetId,
            @Parameter(description = "Page index (0-based)") @PositiveOrZero @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @Positive @RequestParam(defaultValue = "10") int size) {
        log.info("List posting rule versions");
        List<PostingRuleVersionResponse> responses =
                postingRuleService.listVersionsAsResponse(postingRuleSetId, page, size);
        return ResponseEntity.ok(responses);
    }
}
