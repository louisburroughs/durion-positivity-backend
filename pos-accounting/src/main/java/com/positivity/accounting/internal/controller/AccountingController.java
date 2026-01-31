package com.positivity.accounting.internal.controller;

import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stub REST Controller for accounting & finance APIs.
 *
 * Endpoints are catalog-driven and intentionally unimplemented.
 */
@RestController
@RequestMapping("/v1/accounting")
@Tag(name = "Accounting", description = "Miscellaneous accounting endpoints for traceability and payments.")
public class AccountingController {

        private static final Logger log = LoggerFactory.getLogger(AccountingController.class);

        @GetMapping("/traceability/{journalEntryId}")
        @PreAuthorize("hasAuthority('accounting:je:view')")
        @Operation(summary = "Get journal traceability", description = "Trace a journal entry across related records.")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "Traceability returned"),
                        @ApiResponse(responseCode = "404", description = "Journal entry not found")
        })
        public ResponseEntity<Void> getJournalTraceability(
                        @Parameter(description = "Journal entry identifier") @PathVariable String journalEntryId) {
                log.info("Stub getJournalTraceability journalEntryId={}", journalEntryId);
                return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
        }

        @GetMapping("/payments/{paymentId}/status")
        @PreAuthorize("hasAuthority('accounting:ap:view')")
        @Operation(summary = "Get payment status", description = "Retrieve status for an accounts payable payment.")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "Payment status returned"),
                        @ApiResponse(responseCode = "404", description = "Payment not found")
        })
        public ResponseEntity<Void> getPaymentStatus(
                        @Parameter(description = "Payment identifier") @PathVariable String paymentId) {
                log.info("Stub getPaymentStatus paymentId={}", paymentId);
                return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
        }

        @PostMapping("/payments/{paymentId}/void")
        @PreAuthorize("hasAuthority('accounting:ap:pay')")
        @Operation(summary = "Void payment", description = "Void a payment before settlement.")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "Payment voided"),
                        @ApiResponse(responseCode = "404", description = "Payment not found")
        })
        @EmitEvent(id = "ACCOUNTING_PAYMENT_VOID", apiVersion = "1")
        public ResponseEntity<Void> voidPayment(
                        @Parameter(description = "Payment identifier") @PathVariable String paymentId,
                        @RequestBody(required = false) Object body) {
                log.info("Stub voidPayment paymentId={}", paymentId);
                return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
        }

        @PostMapping("/payments/{paymentId}/reverse")
        @PreAuthorize("hasAuthority('accounting:ap:pay')")
        @Operation(summary = "Reverse payment", description = "Reverse a previously applied payment.")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "Payment reversed"),
                        @ApiResponse(responseCode = "404", description = "Payment not found")
        })
        @EmitEvent(id = "ACCOUNTING_PAYMENT_REVERSE", apiVersion = "1")
        public ResponseEntity<Void> reversePayment(
                        @Parameter(description = "Payment identifier") @PathVariable String paymentId,
                        @RequestBody(required = false) Object body) {
                log.info("Stub reversePayment paymentId={}", paymentId);
                return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
        }

        @PostMapping("/glAccounts")
        @PreAuthorize("hasAuthority('accounting:coa:create')")
        @Operation(summary = "Create GL account (legacy path)", description = "Create a GL account via the legacy endpoint.")
        @ApiResponses({
                        @ApiResponse(responseCode = "201", description = "GL account created"),
                        @ApiResponse(responseCode = "400", description = "Invalid request")
        })
        @EmitEvent(id = "ACCOUNTING_GL_ACCOUNT_CREATE_LEGACY", apiVersion = "1")
        public ResponseEntity<Void> createGlAccount(@RequestBody(required = false) Object request) {
                log.info("Stub createGlAccount");
                return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
        }

        @GetMapping("/glAccounts/{accountId}")
        @PreAuthorize("hasAuthority('accounting:coa:view')")
        @Operation(summary = "Get GL account (legacy path)", description = "Retrieve a GL account by identifier.")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "GL account returned"),
                        @ApiResponse(responseCode = "404", description = "GL account not found")
        })
        public ResponseEntity<Void> getGlAccount(
                        @Parameter(description = "GL account identifier") @PathVariable String accountId) {
                log.info("Stub getGlAccount accountId={}", accountId);
                return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
        }

        @PutMapping("/glAccounts/{accountId}")
        @PreAuthorize("hasAuthority('accounting:coa:edit')")
        @Operation(summary = "Update GL account (legacy path)", description = "Update a GL account via the legacy endpoint.")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "GL account updated"),
                        @ApiResponse(responseCode = "404", description = "GL account not found")
        })
        @EmitEvent(id = "ACCOUNTING_GL_ACCOUNT_UPDATE_LEGACY", apiVersion = "1")
        public ResponseEntity<Void> manageGlAccount(
                        @Parameter(description = "GL account identifier") @PathVariable String accountId,
                        @RequestBody(required = false) Object request) {
                log.info("Stub manageGlAccount accountId={}", accountId);
                return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
        }
}
