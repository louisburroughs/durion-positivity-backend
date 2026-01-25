package com.positivity.accounting.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST Controller for Posting Rule Set operations.
 * Handles creation, publishing, and archival of posting rules that drive
 * event-to-JE conversion.
 */
@RestController
@RequestMapping("/v1/accounting/posting-rules")
public class PostingRuleController {

    private static final Logger log = LoggerFactory.getLogger(PostingRuleController.class);

    @GetMapping
    @PreAuthorize("hasAuthority('accounting:posting_rules:view')")
    public ResponseEntity<Void> listPostingRuleSets(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sort) {
        log.info("Stub listPostingRuleSets page={}, size={}, sort={}", page, size, sort);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @GetMapping("/{postingRuleSetId}")
    @PreAuthorize("hasAuthority('accounting:posting_rules:view')")
    public ResponseEntity<Void> getPostingRuleSet(@PathVariable String postingRuleSetId) {
        log.info("Stub getPostingRuleSet postingRuleSetId={}", postingRuleSetId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('accounting:posting_rules:create')")
    public ResponseEntity<Void> createPostingRuleSet(@RequestBody(required = false) Object request) {
        log.info("Stub createPostingRuleSet");
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @PostMapping("/{postingRuleSetId}/publish")
    @PreAuthorize("hasAuthority('accounting:posting_rules:publish')")
    public ResponseEntity<Void> publishPostingRuleSet(
            @PathVariable String postingRuleSetId,
            @RequestBody(required = false) Object request) {
        log.info("Stub publishPostingRuleSet postingRuleSetId={}", postingRuleSetId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @PostMapping("/{postingRuleSetId}/archive")
    @PreAuthorize("hasAuthority('accounting:posting_rules:archive')")
    public ResponseEntity<Void> archivePostingRuleSet(
            @PathVariable String postingRuleSetId,
            @RequestBody(required = false) Object request) {
        log.info("Stub archivePostingRuleSet postingRuleSetId={}", postingRuleSetId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @GetMapping("/{postingRuleSetId}/versions")
    @PreAuthorize("hasAuthority('accounting:posting_rules:view')")
    public ResponseEntity<Void> listPostingRuleVersions(
            @PathVariable String postingRuleSetId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        log.info("Stub listPostingRuleVersions postingRuleSetId={}", postingRuleSetId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
