package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.*;
import com.positivity.accounting.internal.entity.PostingRuleSet;
import com.positivity.accounting.internal.entity.PostingRuleVersion;
import com.positivity.accounting.internal.enums.PostingRuleSetState;
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
                // For now, we pass null for organizationId since entity doesn't support it yet
                List<PostingRuleSet> ruleSets = postingRuleService.listRuleSets(null);
                PostingRuleSetListResponse response = PostingRuleMapper.toListResponse(ruleSets, page, size);
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
                PostingRuleSet ruleSet = postingRuleService.getPostingRuleSet(postingRuleSetId);
                PostingRuleSetResponse response = PostingRuleMapper.toResponse(ruleSet);
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

                // Build rule set with initial DRAFT version — single save via
                // CascadeType.ALL (same pattern as JournalEntry + lines).
                PostingRuleSet ruleSet = PostingRuleMapper.toEntity(request);

                PostingRuleVersion version = new PostingRuleVersion();
                version.setVersionNumber(1);
                version.setState(PostingRuleSetState.DRAFT);
                version.setRulesDefinition(request.getRulesDefinition());
                version.setCreatedBy(request.getCreatedBy());
                version.setModifiedBy(request.getCreatedBy());
                version.setPostingRuleSet(ruleSet);
                ruleSet.getVersions().add(version);

                PostingRuleSet saved = postingRuleService.createPostingRuleSet(ruleSet);
                PostingRuleSetResponse response = PostingRuleMapper.toResponse(saved);

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

                // Find the DRAFT version for this rule set
                List<PostingRuleVersion> versions = postingRuleService.listVersions(postingRuleSetId);
                PostingRuleVersion draftVersion = versions.stream()
                                .filter(v -> v.getState() == PostingRuleSetState.DRAFT)
                                .findFirst()
                                .orElseThrow(() -> new IllegalArgumentException("No DRAFT version found to publish"));

                PostingRuleVersion published = postingRuleService.publishVersion(draftVersion.getVersionId());
                PostingRuleVersionResponse response = PostingRuleMapper.toVersionResponse(published);

                return ResponseEntity.ok(response);
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

                // Find the PUBLISHED version for this rule set
                List<PostingRuleVersion> versions = postingRuleService.listVersions(postingRuleSetId);
                PostingRuleVersion publishedVersion = versions.stream()
                                .filter(v -> v.getState() == PostingRuleSetState.PUBLISHED)
                                .findFirst()
                                .orElseThrow(() -> new IllegalArgumentException(
                                                "No PUBLISHED version found to archive"));

                PostingRuleVersion archived = postingRuleService.archiveVersion(publishedVersion.getVersionId());
                PostingRuleVersionResponse response = PostingRuleMapper.toVersionResponse(archived);

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

                List<PostingRuleVersion> versions = postingRuleService.listVersions(postingRuleSetId);
                List<PostingRuleVersionResponse> responses = versions.stream()
                                .map(PostingRuleMapper::toVersionResponse)
                                .toList();

                return ResponseEntity.ok(responses);
        }
}
