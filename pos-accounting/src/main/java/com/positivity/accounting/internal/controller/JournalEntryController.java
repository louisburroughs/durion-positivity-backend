package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.dto.JournalEntryMapper;
import com.positivity.accounting.internal.dto.JournalEntryResponse;
import com.positivity.accounting.internal.dto.JournalEntryReversalRequest;
import com.positivity.accounting.internal.dto.PagedResponse;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.service.JournalEntryService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST Controller for Journal Entry operations.
 * Handles creation, viewing, posting, and reversal of journal entries.
 */
@RestController
@RequestMapping("/v1/accounting/journal-entries")
@Tag(name = "Journal Entries", description = "Manage journal entries including posting and reversal.")
public class JournalEntryController {

        private static final Logger log = LoggerFactory.getLogger(JournalEntryController.class);
        private final JournalEntryService journalEntryService;

        public JournalEntryController(@NonNull JournalEntryService journalEntryService) {
                this.journalEntryService = journalEntryService;
        }

        @GetMapping
        @PreAuthorize("hasAuthority('accounting:je:view')")
        @Operation(summary = "List journal entries", description = "Retrieve paginated journal entries.")
        @ApiResponse(responseCode = "200", description = "Journal entries listed")
        @ApiResponse(responseCode = "403", description = "Forbidden")
        @EmitEvent(id = "ACCOUNTING_JOURNAL_ENTRY_LIST", apiVersion = "1")
        public ResponseEntity<PagedResponse<JournalEntryResponse>> listJournalEntries(
                        @Parameter(description = "Page index (0-based)") @RequestParam(defaultValue = "0") int page,
                        @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size,
                        @Parameter(description = "Sort field") @RequestParam(defaultValue = "createdAt") String sort) {
                log.debug("Listing journal entries: page={}, size={}, sort={}", page, size, sort);

                Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, sort));
                Page<JournalEntry> entryPage = journalEntryService.listJournalEntries(pageable);

                PagedResponse<JournalEntryResponse> response = new PagedResponse<JournalEntryResponse>(
                                entryPage.getContent().stream()
                                                .map(JournalEntryMapper::toResponse).toList(),
                                entryPage.getNumber(),
                                entryPage.getSize(),
                                entryPage.getTotalElements());

                return ResponseEntity.ok(response);
        }

        @GetMapping("/{journalEntryId}")
        @PreAuthorize("hasAuthority('accounting:je:view')")
        @Operation(summary = "Get journal entry", description = "Retrieve a journal entry by identifier.")
        @ApiResponse(responseCode = "200", description = "Journal entry returned")
        @ApiResponse(responseCode = "404", description = "Journal entry not found")
        public ResponseEntity<JournalEntryResponse> getJournalEntry(
                        @Parameter(description = "Journal entry identifier") @PathVariable UUID journalEntryId) {
                log.debug("Getting journal entry: {}", journalEntryId);
                JournalEntry entry = journalEntryService.getJournalEntry(journalEntryId);
                return ResponseEntity.ok(JournalEntryMapper.toResponse(entry));
        }

        @PostMapping
        @PreAuthorize("hasAuthority('accounting:je:create')")
        @Operation(summary = "Create journal entry", description = "Create a new journal entry.")
        @ApiResponse(responseCode = "201", description = "Journal entry created")
        @ApiResponse(responseCode = "400", description = "Invalid request")
        @EmitEvent(id = "ACCOUNTING_JOURNAL_ENTRY_CREATE", apiVersion = "1")
        public ResponseEntity<JournalEntryResponse> createJournalEntry(
                        @Valid @RequestBody JournalEntryCreateRequest request) {
                log.debug("Creating journal entry: {}", request.getDescription());
                JournalEntry entity = JournalEntryMapper.toEntity(request);
                JournalEntry created = journalEntryService.createJournalEntry(entity);
                return ResponseEntity.status(HttpStatus.CREATED).body(JournalEntryMapper.toResponse(created));
        }

        @PutMapping("/{journalEntryId}")
        @PreAuthorize("hasAuthority('accounting:je:create')")
        @Operation(summary = "Update journal entry", description = "Update an existing journal entry.")
        @ApiResponse(responseCode = "200", description = "Journal entry updated")
        @ApiResponse(responseCode = "404", description = "Journal entry not found")
        @EmitEvent(id = "ACCOUNTING_JOURNAL_ENTRY_UPDATE", apiVersion = "1")
        public ResponseEntity<JournalEntryResponse> updateJournalEntry(
                        @Parameter(description = "Journal entry identifier") @PathVariable UUID journalEntryId,
                        @Valid @RequestBody JournalEntryCreateRequest request) {
                log.debug("Updating journal entry: {}", journalEntryId);
                JournalEntry updates = JournalEntryMapper.toEntity(request);
                JournalEntry updated = journalEntryService.updateJournalEntry(journalEntryId, updates);
                return ResponseEntity.ok(JournalEntryMapper.toResponse(updated));
        }

        @PostMapping("/{journalEntryId}/post")
        @PreAuthorize("hasAuthority('accounting:je:post')")
        @Operation(summary = "Post journal entry", description = "Post a draft journal entry to the ledger.")
        @ApiResponse(responseCode = "200", description = "Journal entry posted")
        @ApiResponse(responseCode = "404", description = "Journal entry not found")
        @EmitEvent(id = "ACCOUNTING_JOURNAL_ENTRY_POST", apiVersion = "1")
        public ResponseEntity<JournalEntryResponse> postJournalEntry(
                        @Parameter(description = "Journal entry identifier") @PathVariable UUID journalEntryId,
                        @RequestBody(required = false) Object request) {
                log.info("Posting journal entry: {}", journalEntryId);
                JournalEntry posted = journalEntryService.postJournalEntry(journalEntryId);
                return ResponseEntity.ok(JournalEntryMapper.toResponse(posted));
        }

        @PostMapping("/{journalEntryId}/reverse")
        @PreAuthorize("hasAuthority('accounting:je:reverse')")
        @Operation(summary = "Reverse journal entry", description = "Reverse a posted journal entry.")
        @ApiResponse(responseCode = "200", description = "Journal entry reversed")
        @ApiResponse(responseCode = "404", description = "Journal entry not found")
        @EmitEvent(id = "ACCOUNTING_JOURNAL_ENTRY_REVERSE", apiVersion = "1")
        public ResponseEntity<JournalEntryResponse> reverseJournalEntry(
                        @Parameter(description = "Journal entry identifier") @PathVariable UUID journalEntryId,
                        @Valid @RequestBody JournalEntryReversalRequest request) {
                log.info("Reversing journal entry: {} with reason: {}", journalEntryId, request.getReason());
                JournalEntry reversed = journalEntryService.reverseJournalEntry(journalEntryId, request.getReason());
                return ResponseEntity.ok(JournalEntryMapper.toResponse(reversed));
        }
}
