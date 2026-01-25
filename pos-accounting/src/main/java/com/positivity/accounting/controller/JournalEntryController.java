package com.positivity.accounting.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST Controller for Journal Entry operations.
 * Handles creation, viewing, posting, and reversal of journal entries.
 */
@RestController
@RequestMapping("/v1/accounting/journal-entries")
public class JournalEntryController {

    private static final Logger log = LoggerFactory.getLogger(JournalEntryController.class);

    @GetMapping
    @PreAuthorize("hasAuthority('accounting:je:view')")
    public ResponseEntity<Void> listJournalEntries(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sort) {
        log.info("Stub listJournalEntries page={}, size={}, sort={}", page, size, sort);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @GetMapping("/{journalEntryId}")
    @PreAuthorize("hasAuthority('accounting:je:view')")
    public ResponseEntity<Void> getJournalEntry(@PathVariable String journalEntryId) {
        log.info("Stub getJournalEntry journalEntryId={}", journalEntryId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('accounting:je:create')")
    public ResponseEntity<Void> createJournalEntry(@RequestBody(required = false) Object request) {
        log.info("Stub createJournalEntry");
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @PutMapping("/{journalEntryId}")
    @PreAuthorize("hasAuthority('accounting:je:create')")
    public ResponseEntity<Void> updateJournalEntry(
            @PathVariable String journalEntryId,
            @RequestBody(required = false) Object request) {
        log.info("Stub updateJournalEntry journalEntryId={}", journalEntryId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @PostMapping("/{journalEntryId}/post")
    @PreAuthorize("hasAuthority('accounting:je:post')")
    public ResponseEntity<Void> postJournalEntry(
            @PathVariable String journalEntryId,
            @RequestBody(required = false) Object request) {
        log.info("Stub postJournalEntry journalEntryId={}", journalEntryId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @PostMapping("/{journalEntryId}/reverse")
    @PreAuthorize("hasAuthority('accounting:je:reverse')")
    public ResponseEntity<Void> reverseJournalEntry(
            @PathVariable String journalEntryId,
            @RequestBody(required = false) Object request) {
        log.info("Stub reverseJournalEntry journalEntryId={}", journalEntryId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
