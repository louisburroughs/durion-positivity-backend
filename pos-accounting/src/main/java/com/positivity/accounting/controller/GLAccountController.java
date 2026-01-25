package com.positivity.accounting.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST Controller for GL Account (Chart of Accounts) management.
 * Handles CRUD operations, activation/deactivation, and archival of GL
 * accounts.
 */
@RestController
@RequestMapping("/v1/accounting/gl-accounts")
public class GLAccountController {

    private static final Logger log = LoggerFactory.getLogger(GLAccountController.class);

    @GetMapping
    @PreAuthorize("hasAuthority('accounting:coa:view')")
    public ResponseEntity<Void> listGLAccounts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "accountNumber") String sort,
            @RequestParam(required = false) String status) {
        log.info("Stub listGLAccounts page={}, size={}, sort={}, status={}", page, size, sort, status);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @GetMapping("/{glAccountId}")
    @PreAuthorize("hasAuthority('accounting:coa:view')")
    public ResponseEntity<Void> getGLAccount(@PathVariable String glAccountId) {
        log.info("Stub getGLAccount glAccountId={}", glAccountId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('accounting:coa:create')")
    public ResponseEntity<Void> createGLAccount(@RequestBody(required = false) Object request) {
        log.info("Stub createGLAccount");
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @PutMapping("/{glAccountId}")
    @PreAuthorize("hasAuthority('accounting:coa:edit')")
    public ResponseEntity<Void> updateGLAccount(
            @PathVariable String glAccountId,
            @RequestBody(required = false) Object request) {
        log.info("Stub updateGLAccount glAccountId={}", glAccountId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @PostMapping("/{glAccountId}/activate")
    @PreAuthorize("hasAuthority('accounting:coa:edit')")
    public ResponseEntity<Void> activateGLAccount(
            @PathVariable String glAccountId,
            @RequestBody(required = false) Object request) {
        log.info("Stub activateGLAccount glAccountId={}", glAccountId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @PostMapping("/{glAccountId}/deactivate")
    @PreAuthorize("hasAuthority('accounting:coa:deactivate')")
    public ResponseEntity<Void> deactivateGLAccount(
            @PathVariable String glAccountId,
            @RequestBody(required = false) Object request) {
        log.info("Stub deactivateGLAccount glAccountId={}", glAccountId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @PostMapping("/{glAccountId}/archive")
    @PreAuthorize("hasAuthority('accounting:coa:deactivate')")
    public ResponseEntity<Void> archiveGLAccount(
            @PathVariable String glAccountId,
            @RequestBody(required = false) Object request) {
        log.info("Stub archiveGLAccount glAccountId={}", glAccountId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @GetMapping("/{glAccountId}/balance")
    @PreAuthorize("hasAuthority('accounting:coa:view')")
    public ResponseEntity<Void> getAccountBalance(@PathVariable String glAccountId) {
        log.info("Stub getAccountBalance glAccountId={}", glAccountId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
