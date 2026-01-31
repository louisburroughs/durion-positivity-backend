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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller for GL Account (Chart of Accounts) management.
 * Handles CRUD operations, activation/deactivation, and archival of GL
 * accounts.
 */
@RestController
@RequestMapping("/v1/accounting/gl-accounts")
@Tag(name = "GL Accounts", description = "Manage chart of accounts including lifecycle actions.")
public class GLAccountController {

        private static final Logger log = LoggerFactory.getLogger(GLAccountController.class);

        @GetMapping
        @PreAuthorize("hasAuthority('accounting:coa:view')")
        @Operation(summary = "List GL accounts", description = "Retrieve paginated GL accounts filtered by status and sorted by a field.")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "GL accounts listed"),
                        @ApiResponse(responseCode = "403", description = "Forbidden")
        })
        @EmitEvent(id = "ACCOUNTING_GL_ACCOUNT_LIST", apiVersion = "1")
        public ResponseEntity<Void> listGLAccounts(
                        @Parameter(description = "Page index (0-based)") @RequestParam(defaultValue = "0") int page,
                        @Parameter(description = "Page size") @RequestParam(defaultValue = "20") int size,
                        @Parameter(description = "Sort field") @RequestParam(defaultValue = "accountNumber") String sort,
                        @Parameter(description = "Filter by account status") @RequestParam(required = false) String status) {
                log.info("Stub listGLAccounts page={}, size={}, sort={}, status={}", page, size, sort, status);
                return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
        }

        @GetMapping("/{glAccountId}")
        @PreAuthorize("hasAuthority('accounting:coa:view')")
        @Operation(summary = "Get GL account", description = "Retrieve a GL account by identifier.")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "GL account returned"),
                        @ApiResponse(responseCode = "404", description = "GL account not found")
        })
        public ResponseEntity<Void> getGLAccount(
                        @Parameter(description = "GL account identifier") @PathVariable String glAccountId) {
                log.info("Stub getGLAccount glAccountId={}", glAccountId);
                return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
        }

        @PostMapping
        @PreAuthorize("hasAuthority('accounting:coa:create')")
        @Operation(summary = "Create GL account", description = "Create a new GL account.")
        @ApiResponses({
                        @ApiResponse(responseCode = "201", description = "GL account created"),
                        @ApiResponse(responseCode = "400", description = "Invalid request")
        })
        @EmitEvent(id = "ACCOUNTING_GL_ACCOUNT_CREATE", apiVersion = "1")
        public ResponseEntity<Void> createGLAccount(@RequestBody(required = false) Object request) {
                log.info("Stub createGLAccount");
                return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
        }

        @PutMapping("/{glAccountId}")
        @PreAuthorize("hasAuthority('accounting:coa:edit')")
        @Operation(summary = "Update GL account", description = "Update details for an existing GL account.")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "GL account updated"),
                        @ApiResponse(responseCode = "404", description = "GL account not found")
        })
        @EmitEvent(id = "ACCOUNTING_GL_ACCOUNT_UPDATE", apiVersion = "1")
        public ResponseEntity<Void> updateGLAccount(
                        @Parameter(description = "GL account identifier") @PathVariable String glAccountId,
                        @RequestBody(required = false) Object request) {
                log.info("Stub updateGLAccount glAccountId={}", glAccountId);
                return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
        }

        @PostMapping("/{glAccountId}/activate")
        @PreAuthorize("hasAuthority('accounting:coa:edit')")
        @Operation(summary = "Activate GL account", description = "Mark a GL account as active.")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "GL account activated"),
                        @ApiResponse(responseCode = "404", description = "GL account not found")
        })
        @EmitEvent(id = "ACCOUNTING_GL_ACCOUNT_ACTIVATE", apiVersion = "1")
        public ResponseEntity<Void> activateGLAccount(
                        @Parameter(description = "GL account identifier") @PathVariable String glAccountId,
                        @RequestBody(required = false) Object request) {
                log.info("Stub activateGLAccount glAccountId={}", glAccountId);
                return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
        }

        @PostMapping("/{glAccountId}/deactivate")
        @PreAuthorize("hasAuthority('accounting:coa:deactivate')")
        @Operation(summary = "Deactivate GL account", description = "Mark a GL account as inactive.")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "GL account deactivated"),
                        @ApiResponse(responseCode = "404", description = "GL account not found")
        })
        @EmitEvent(id = "ACCOUNTING_GL_ACCOUNT_DEACTIVATE", apiVersion = "1")
        public ResponseEntity<Void> deactivateGLAccount(
                        @Parameter(description = "GL account identifier") @PathVariable String glAccountId,
                        @RequestBody(required = false) Object request) {
                log.info("Stub deactivateGLAccount glAccountId={}", glAccountId);
                return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
        }

        @PostMapping("/{glAccountId}/archive")
        @PreAuthorize("hasAuthority('accounting:coa:deactivate')")
        @Operation(summary = "Archive GL account", description = "Archive a GL account and remove it from active use.")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "GL account archived"),
                        @ApiResponse(responseCode = "404", description = "GL account not found")
        })
        @EmitEvent(id = "ACCOUNTING_GL_ACCOUNT_ARCHIVE", apiVersion = "1")
        public ResponseEntity<Void> archiveGLAccount(
                        @Parameter(description = "GL account identifier") @PathVariable String glAccountId,
                        @RequestBody(required = false) Object request) {
                log.info("Stub archiveGLAccount glAccountId={}", glAccountId);
                return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
        }

        @GetMapping("/{glAccountId}/balance")
        @PreAuthorize("hasAuthority('accounting:coa:view')")
        @Operation(summary = "Get GL account balance", description = "Retrieve the current balance for a GL account.")
        @ApiResponses({
                        @ApiResponse(responseCode = "200", description = "Balance returned"),
                        @ApiResponse(responseCode = "404", description = "GL account not found")
        })
        public ResponseEntity<Void> getAccountBalance(
                        @Parameter(description = "GL account identifier") @PathVariable String glAccountId) {
                log.info("Stub getAccountBalance glAccountId={}", glAccountId);
                return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
        }
}
