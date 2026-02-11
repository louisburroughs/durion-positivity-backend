package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.PostingRuleSetCreateRequest;
import com.positivity.accounting.internal.dto.PostingRuleSetListResponse;
import com.positivity.accounting.internal.dto.PostingRuleSetResponse;
import com.positivity.accounting.internal.dto.PostingRuleVersionResponse;
import com.positivity.accounting.service.PostingRuleService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;

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
        @PreAuthorize("hasAuthority('accounting:posting_rules:view')")
        @Operation(summary = "List posting rule sets", description = "Retrieve paginated posting rule sets.")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "Posting rule sets listed"),
                        @ApiResponse(responseCode = "403", description = "Forbidden")
        })
        @EmitEvent(id = "ACCOUNTING_POSTING_RULE_LIST", apiVersion = "1")
        public ResponseEntity<PostingRuleSetListResponse> listPostingRuleSets(
                        @Parameter(description = "Page index (0-based)") @RequestParam(defaultValue = "0") int page,
                        @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size,
                        @Parameter(description = "Sort field") @RequestParam(defaultValue = "createdAt") String sort) {
                log.info("List posting rule sets - page={}, size={}, sort={}", page, size, sort);
                PostingRuleSetListResponse response = postingRuleService.listRuleSetsAsResponse(page, size, sort);
                return ResponseEntity.ok(response);
        }

        @GetMapping("/{postingRuleSetId}")
        @PreAuthorize("hasAuthority('accounting:posting_rules:view')")
        @Operation(summary = "Get posting rule set", description = "Retrieve a posting rule set by identifier.")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "Posting rule set returned"),
                        @ApiResponse(responseCode = "404", description = "Posting rule set not found")
        })
        public ResponseEntity<PostingRuleSetResponse> getPostingRuleSet(
                        @Parameter(description = "Posting rule set identifier") @PathVariable UUID postingRuleSetId) {
                log.info("Get posting rule set - ruleSetId={}", postingRuleSetId);
                PostingRuleSetResponse response = postingRuleService.getPostingRuleSetAsResponse(postingRuleSetId);
                return ResponseEntity.ok(response);
        }

        @PostMapping
        @PreAuthorize("hasAuthority('accounting:posting_rules:create')")
        @Operation(summary = "Create posting rule set", description = "Create a new posting rule set with initial DRAFT version.")
        @ApiResponses({
                        @ApiResponse(responseCode = "201", description = "Posting rule set created"),
                        @ApiResponse(responseCode = "400", description = "Invalid request")
        })
        @EmitEvent(id = "ACCOUNTING_POSTING_RULE_CREATE", apiVersion = "1")
        public ResponseEntity<PostingRuleSetResponse> createPostingRuleSet(
                        @Valid @RequestBody PostingRuleSetCreateRequest request) {
                log.info("Create posting rule set - name={}, eventType={}", request.getName(), request.getEventType());
                PostingRuleSetResponse response = postingRuleService.createPostingRuleSetWithVersion(request);
                return ResponseEntity.status(HttpStatus.CREATED).body(response);
        }

        @PostMapping("/{postingRuleSetId}/publish")
        @PreAuthorize("hasAuthority('accounting:posting_rules:publish')")
        @Operation(summary = "Publish posting rule set", description = "Publish the latest DRAFT version.")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "Posting rule set published"),
                        @ApiResponse(responseCode = "404", description = "Posting rule set not found"),
                        @ApiResponse(responseCode = "400", description = "No DRAFT version to publish")
        })
        @EmitEvent(id = "ACCOUNTING_POSTING_RULE_PUBLISH", apiVersion = "1")
        public ResponseEntity<PostingRuleVersionResponse> publishPostingRuleSet(
                        @Parameter(description = "Posting rule set identifier") @PathVariable UUID postingRuleSetId) {
                log.info("Publish posting rule set - ruleSetId={}", postingRuleSetId);
                PostingRuleVersionResponse response = postingRuleService.publishRuleSet(postingRuleSetId);
                return ResponseEntity.ok(response);
        }

        @PutMapping("/{postingRuleSetId}")
        @PreAuthorize("hasAuthority('accounting:posting_rules:create')")
        @Operation(summary = "Update posting rule set metadata", description = "Update a posting rule set (only possible if no PUBLISHED version exists).")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "Posting rule set updated"),
                        @ApiResponse(responseCode = "404", description = "Posting rule set not found"),
                        @ApiResponse(responseCode = "409", description = "Cannot modify published rule set")
        })
        public ResponseEntity<PostingRuleSetResponse> updatePostingRuleSet(
                        @Parameter(description = "Posting rule set identifier") @PathVariable UUID postingRuleSetId,
                        @Valid @RequestBody PostingRuleSetCreateRequest request) {
                log.info("Update posting rule set - ruleSetId={}, name={}", postingRuleSetId, request.getName());
                try {
                        // Fetch current ruleset
                        PostingRuleSetResponse response = postingRuleService
                                        .getPostingRuleSetAsResponse(postingRuleSetId);
                        // Build update entity from request
                        com.positivity.accounting.internal.entity.PostingRuleSet updateEntity = new com.positivity.accounting.internal.entity.PostingRuleSet();
                        updateEntity.setName(request.getName());
                        updateEntity.setEventType(request.getEventType());
                        updateEntity.setDescription(request.getDescription());
                        postingRuleService.updatePostingRuleSet(postingRuleSetId, updateEntity);
                        response = postingRuleService.getPostingRuleSetAsResponse(postingRuleSetId);
                        return ResponseEntity.ok(response);
                } catch (IllegalStateException e) {
                        // Convert to 409 Conflict for published ruleset
                        log.warn("Cannot modify published rule set: {}", postingRuleSetId);
                        return ResponseEntity.status(HttpStatus.CONFLICT).build();
                }
        }

        @PostMapping("/{postingRuleSetId}/archive")
        @PreAuthorize("hasAuthority('accounting:posting_rules:archive')")
        @Operation(summary = "Archive posting rule set", description = "Archive the PUBLISHED version.")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "Posting rule set archived"),
                        @ApiResponse(responseCode = "404", description = "Posting rule set not found"),
                        @ApiResponse(responseCode = "400", description = "No PUBLISHED version to archive")
        })
        @EmitEvent(id = "ACCOUNTING_POSTING_RULE_ARCHIVE", apiVersion = "1")
        public ResponseEntity<PostingRuleVersionResponse> archivePostingRuleSet(
                        @Parameter(description = "Posting rule set identifier") @PathVariable UUID postingRuleSetId) {
                log.info("Archive posting rule set - ruleSetId={}", postingRuleSetId);
                PostingRuleVersionResponse response = postingRuleService.archiveRuleSet(postingRuleSetId);
                return ResponseEntity.ok(response);
        }

        @GetMapping("/{postingRuleSetId}/versions")
        @PreAuthorize("hasAuthority('accounting:posting_rules:view')")
        @Operation(summary = "List posting rule versions", description = "List versions for a posting rule set.")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "Posting rule versions listed"),
                        @ApiResponse(responseCode = "404", description = "Posting rule set not found")
        })
        public ResponseEntity<List<PostingRuleVersionResponse>> listPostingRuleVersions(
                        @Parameter(description = "Posting rule set identifier") @PathVariable UUID postingRuleSetId,
                        @Parameter(description = "Page index (0-based)") @RequestParam(defaultValue = "0") int page,
                        @Parameter(description = "Page size") @RequestParam(defaultValue = "10") int size) {
                log.info("List posting rule versions - ruleSetId={}, page={}, size={}", postingRuleSetId, page, size);
                List<PostingRuleVersionResponse> responses = postingRuleService
                                .listVersionsAsResponse(postingRuleSetId, page, size);
                return ResponseEntity.ok(responses);
        }
}
