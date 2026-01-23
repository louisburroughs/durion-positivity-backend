package com.positivity.accounting.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Stub REST Controller for accounting & finance APIs.
 *
 * Endpoints are catalog-driven and intentionally unimplemented.
 */
@RestController
@RequestMapping("/v1/accounting")
public class AccountingController {

    private static final Logger log = LoggerFactory.getLogger(AccountingController.class);

    @GetMapping("/traceability/{journalEntryId}")
    public ResponseEntity<Void> getJournalTraceability(@PathVariable String journalEntryId) {
        log.info("Stub getJournalTraceability journalEntryId={}", journalEntryId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @GetMapping("/payments/{paymentId}/status")
    public ResponseEntity<Void> getPaymentStatus(@PathVariable String paymentId) {
        log.info("Stub getPaymentStatus paymentId={}", paymentId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @PostMapping("/payments/{paymentId}/void")
    public ResponseEntity<Void> voidPayment(
            @PathVariable String paymentId,
            @RequestBody(required = false) Object body) {
        log.info("Stub voidPayment paymentId={}", paymentId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @PostMapping("/payments/{paymentId}/reverse")
    public ResponseEntity<Void> reversePayment(
            @PathVariable String paymentId,
            @RequestBody(required = false) Object body) {
        log.info("Stub reversePayment paymentId={}", paymentId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @PostMapping("/glAccounts")
    public ResponseEntity<Void> createGlAccount(@RequestBody(required = false) Object request) {
        log.info("Stub createGlAccount");
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @GetMapping("/glAccounts/{accountId}")
    public ResponseEntity<Void> getGlAccount(@PathVariable String accountId) {
        log.info("Stub getGlAccount accountId={}", accountId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @PutMapping("/glAccounts/{accountId}")
    public ResponseEntity<Void> manageGlAccount(
            @PathVariable String accountId,
            @RequestBody(required = false) Object request) {
        log.info("Stub manageGlAccount accountId={}", accountId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
