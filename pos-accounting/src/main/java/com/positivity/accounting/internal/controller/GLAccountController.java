package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.GLAccountBalanceResponse;
import com.positivity.accounting.internal.dto.GLAccountCreateRequest;
import com.positivity.accounting.internal.dto.GLAccountListResponse;
import com.positivity.accounting.internal.dto.GLAccountResponse;
import com.positivity.accounting.internal.dto.GLAccountUpdateRequest;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.GLAccountService;
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
 * REST Controller for GL Account (Chart of Accounts management).
 * Handles CRUD operations, activation/deactivation, and archival of GL
 * accounts.
 */
@RestController
@RequestMapping("/v1/accounting/gl-accounts")
@Tag(name = "GL Accounts", description = "Manage chart of accounts including lifecycle actions.")
@RequiredArgsConstructor
@Validated
public class GLAccountController {

    private static final Logger log = LoggerFactory.getLogger(GLAccountController.class);

    private final GLAccountService glAccountService;

    @GetMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:coa:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.COA_VIEW + "')")
    @Operation(
            operationId = "listGLAccounts",
            summary = "List GL Accounts",
            description = """
                    Lists the chart of accounts as a paginated projection, optionally filtered by derived \
                    lifecycle status and sorted by a named field.
                    Use this tool when browsing or searching the chart of accounts; do not use getGLAccount, \
                    which retrieves one account by its known id.
                    Preconditions: none beyond the caller holding accounting:coa:view; an empty chart returns \
                    an empty page rather than an error.
                    Required inputs: none; page defaults to 0, size to 20, sort to accountCode ascending \
                    (format 'field,direction'), and status optionally filters by ACTIVE, INACTIVE or NOT_YET_ACTIVE.
                    Emits an ACCOUNTING_GL_ACCOUNT_LIST audit event; no accounting state changes.
                    Returns 400 when the sort property is not one of the supported fields.
                    """,
            tags = {"GL Accounts"})
    @ApiResponse(responseCode = "200", description = "GL accounts listed")
    @ApiResponse(responseCode = "400", description = "Unsupported sort property or direction")
    @ApiResponse(responseCode = "403", description = "Forbidden")
    @EmitEvent(id = "ACCOUNTING_GL_ACCOUNT_LIST", apiVersion = "1")
    public ResponseEntity<GLAccountListResponse> listGLAccounts(
            @Parameter(description = "Page index (0-based)") @PositiveOrZero @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @Positive @RequestParam(defaultValue = "20") int size,
            @Parameter(
                            description = "Sort field with optional direction, e.g. 'modifiedAt,desc'. "
                                    + "Supported fields: accountCode, accountName, accountType, description, "
                                    + "activationDate, deactivationDate, createdAt, modifiedAt, updatedAt, "
                                    + "glAccountId. Direction defaults to asc.")
                    @NotBlank
                    @RequestParam(defaultValue = "accountCode")
                    String sort,
            @Parameter(description = "Filter by account status") @RequestParam(required = false) String status) {
        log.info("List GL accounts");
        GLAccountListResponse response = glAccountService.listGLAccounts(page, size, sort, status);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{glAccountId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:coa:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.COA_VIEW + "')")
    @Operation(
            operationId = "getGLAccount",
            summary = "Get GL Account",
            description = """
                    Returns one GL account with its code, name, type, subtype, reconcilable flag and derived \
                    lifecycle status.
                    Use this tool when the GL account id is already known; use listGLAccounts instead when \
                    searching by code, name or status.
                    Preconditions: the GL account must exist.
                    Required inputs: glAccountId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no GL account exists for the supplied id.
                    """,
            tags = {"GL Accounts"})
    @ApiResponse(responseCode = "200", description = "GL account returned")
    @ApiResponse(responseCode = "404", description = "GL account not found")
    public ResponseEntity<GLAccountResponse> getGLAccount(
            @Parameter(description = "GL account identifier") @PathVariable UUID glAccountId) {
        log.info("Get GL account");
        GLAccountResponse response = glAccountService.getGLAccount(glAccountId);
        return ResponseEntity.ok(response);
    }

    @PostMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:coa:create"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.COA_CREATE + "')")
    @Operation(
            operationId = "createGLAccount",
            summary = "Create GL Account",
            description = """
                    Creates a GL account in the chart of accounts with a unique account code, classification \
                    and activation date.
                    Use this tool when adding a new account to the chart; do not use updateGLAccount, which \
                    edits the mutable fields of an account that already exists.
                    Preconditions: no account may already exist with the same accountCode; a parentAccountId, \
                    when supplied, must reference an existing account.
                    Required inputs: accountCode (pattern #### or ####-###), accountName, accountType (ASSET, \
                    LIABILITY, EQUITY, REVENUE or EXPENSE) and activationDate; accountSubtype, description and \
                    parentAccountId are optional, and reconcilable defaults to false.
                    Emits an ACCOUNTING_GL_ACCOUNT_CREATE event; the account code and type are immutable after \
                    creation.
                    Returns 409 when the account code already exists, and 400 when the parent account cannot \
                    be resolved.
                    """,
            tags = {"GL Accounts"})
    @ApiResponse(responseCode = "201", description = "GL account created")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @EmitEvent(id = "ACCOUNTING_GL_ACCOUNT_CREATE", apiVersion = "1")
    public ResponseEntity<GLAccountResponse> createGLAccount(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "GL account definition to add to the chart of accounts.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Cash account", value = """
                                                                    {"accountCode":"1000",
                                                                     "accountName":"Cash on Hand",
                                                                     "accountType":"ASSET",
                                                                     "accountSubtype":"BANK_CASH",
                                                                     "reconcilable":true,
                                                                     "description":"Register and vault cash",
                                                                     "activationDate":"2026-01-01T00:00:00"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    GLAccountCreateRequest request) {
        log.info("Create GL account request received");
        GLAccountResponse response = glAccountService.createGLAccount(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{glAccountId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:coa:edit"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.COA_EDIT + "')")
    @Operation(
            operationId = "updateGLAccount",
            summary = "Update GL Account",
            description = """
                    Updates the mutable descriptive fields of an existing GL account: name, description, \
                    subtype and the reconcilable flag.
                    Use this tool when correcting or enriching an account's metadata; do not use \
                    createGLAccount, which adds a new account, and note that accountCode and accountType are \
                    immutable and cannot be changed by any operation.
                    Preconditions: the GL account must exist.
                    Required inputs: glAccountId (UUID) as a path parameter; all body fields are optional and \
                    only supplied fields are changed (accountName max 100 chars, description max 500).
                    Emits an ACCOUNTING_GL_ACCOUNT_UPDATE event; posted journal entries referencing the \
                    account are unaffected.
                    Returns 404 when no GL account exists for the supplied id.
                    """,
            tags = {"GL Accounts"})
    @ApiResponse(responseCode = "200", description = "GL account updated")
    @ApiResponse(responseCode = "404", description = "GL account not found")
    @EmitEvent(id = "ACCOUNTING_GL_ACCOUNT_UPDATE", apiVersion = "1")
    public ResponseEntity<GLAccountResponse> updateGLAccount(
            @Parameter(description = "GL account identifier") @PathVariable UUID glAccountId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Mutable GL account fields to change; omitted fields keep their current values.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(name = "Rename and mark reconcilable", value = """
                                                                    {"accountName":"Operating Cash",
                                                                     "description":"Primary operating bank account",
                                                                     "accountSubtype":"BANK_CASH",
                                                                     "reconcilable":true}
                                                                    """)))
                    @Valid
                    @RequestBody
                    GLAccountUpdateRequest request) {
        log.info("Update GL account");
        GLAccountResponse response = glAccountService.updateGLAccount(glAccountId, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{glAccountId}/activate")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:coa:edit"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.COA_EDIT + "')")
    @Operation(
            operationId = "activateGLAccount",
            summary = "Activate GL Account",
            description = """
                    Activates a GL account for posting by setting its activation date to the start of the \
                    supplied effective date and clearing any deactivation date.
                    Use this tool when re-enabling an inactive account or bringing a not-yet-active account \
                    into service; do not use deactivateGLAccount, which does the reverse.
                    Preconditions: the GL account must exist; activation is idempotent and simply resets the \
                    effective dates.
                    Required inputs: glAccountId (UUID) as a path parameter and effectiveDate (ISO date) in \
                    the body; the account becomes ACTIVE at start of day on that date.
                    Emits an ACCOUNTING_GL_ACCOUNT_ACTIVATE event; no journal entries are created or altered.
                    Returns 404 when no GL account exists for the supplied id.
                    """,
            tags = {"GL Accounts"})
    @ApiResponse(responseCode = "200", description = "GL account activated")
    @ApiResponse(responseCode = "404", description = "GL account not found")
    @EmitEvent(id = "ACCOUNTING_GL_ACCOUNT_ACTIVATE", apiVersion = "1")
    public ResponseEntity<GLAccountResponse> activateGLAccount(
            @Parameter(description = "GL account identifier") @PathVariable UUID glAccountId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Effective date on which the account becomes active for posting.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Activate at month start",
                                                            value = "{\"effectiveDate\":\"2026-09-01\"}")))
                    @Valid
                    @RequestBody
                    com.positivity.accounting.internal.dto.GLAccountActivateRequest request) {
        log.info("Activate GL account");
        GLAccountResponse response = glAccountService.activateGLAccount(
                glAccountId, request.getEffectiveDate().atStartOfDay());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{glAccountId}/deactivate")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:coa:deactivate"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.COA_DEACTIVATE + "')")
    @Operation(
            operationId = "deactivateGLAccount",
            summary = "Deactivate GL Account",
            description = """
                    Deactivates a GL account by stamping its deactivation date now, preventing future postings \
                    to it.
                    Use this tool when retiring an account from active use; do not use archiveGLAccount, which \
                    additionally flags an already-INACTIVE account as archived, and do not use it while the \
                    account still carries a balance.
                    Preconditions: the GL account must exist and its posted balance must be exactly zero.
                    Required inputs: glAccountId (UUID) as a path parameter; the request body is optional and \
                    ignored.
                    Emits an ACCOUNTING_GL_ACCOUNT_DEACTIVATE event; historical journal entries remain intact.
                    Returns 404 when no GL account exists for the supplied id, and 409 ACCOUNT_NOT_ZERO_BALANCE \
                    when the account balance is not zero.
                    """,
            tags = {"GL Accounts"})
    @ApiResponse(responseCode = "200", description = "GL account deactivated")
    @ApiResponse(responseCode = "404", description = "GL account not found")
    @ApiResponse(responseCode = "409", description = "Account balance is not zero (ACCOUNT_NOT_ZERO_BALANCE)")
    @EmitEvent(id = "ACCOUNTING_GL_ACCOUNT_DEACTIVATE", apiVersion = "1")
    public ResponseEntity<GLAccountResponse> deactivateGLAccount(
            @Parameter(description = "GL account identifier") @PathVariable UUID glAccountId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Optional and ignored; send an empty object or omit the body entirely.",
                            required = false,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Empty body", value = "{}")))
                    @RequestBody(required = false)
                    Object request) {
        log.info("Deactivate GL account");
        GLAccountResponse response = glAccountService.deactivateGLAccount(glAccountId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{glAccountId}/archive")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:coa:deactivate"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.COA_DEACTIVATE + "')")
    @Operation(
            operationId = "archiveGLAccount",
            summary = "Archive GL Account",
            description = """
                    Archives an inactive GL account as a soft delete, tagging it [ARCHIVED] while keeping the \
                    record and its posting history.
                    Use this tool only after deactivateGLAccount has succeeded; do not use it on an ACTIVE \
                    account, which must be deactivated to zero balance first.
                    Preconditions: the GL account must exist and its derived status must be INACTIVE.
                    Required inputs: glAccountId (UUID) as a path parameter; the request body is optional and \
                    ignored.
                    Emits an ACCOUNTING_GL_ACCOUNT_ARCHIVE event; the row is never physically deleted.
                    Returns 404 when no GL account exists for the supplied id, and 409 ACCOUNT_NOT_INACTIVE \
                    when the account is not INACTIVE.
                    """,
            tags = {"GL Accounts"})
    @ApiResponse(responseCode = "200", description = "GL account archived")
    @ApiResponse(responseCode = "404", description = "GL account not found")
    @ApiResponse(responseCode = "409", description = "Account is not INACTIVE (ACCOUNT_NOT_INACTIVE)")
    @EmitEvent(id = "ACCOUNTING_GL_ACCOUNT_ARCHIVE", apiVersion = "1")
    public ResponseEntity<GLAccountResponse> archiveGLAccount(
            @Parameter(description = "GL account identifier") @PathVariable UUID glAccountId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Optional and ignored; send an empty object or omit the body entirely.",
                            required = false,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Empty body", value = "{}")))
                    @RequestBody(required = false)
                    Object request) {
        log.info("Archive GL account");
        GLAccountResponse response = glAccountService.archiveGLAccount(glAccountId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{glAccountId}/balance")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:coa:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.COA_VIEW + "')")
    @Operation(
            operationId = "getGLAccountBalance",
            summary = "Get GL Account Balance",
            description = """
                    Returns the current posted balance of one GL account, computed from its journal entry \
                    lines.
                    Use this tool for a single account's balance; use the trial balance report in \
                    financial reporting instead when balances for the whole chart are needed.
                    Preconditions: the GL account must exist; an account with no postings reports a zero \
                    balance.
                    Required inputs: glAccountId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no GL account exists for the supplied id.
                    """,
            tags = {"GL Accounts"})
    @ApiResponse(responseCode = "200", description = "Balance returned")
    @ApiResponse(responseCode = "404", description = "GL account not found")
    public ResponseEntity<GLAccountBalanceResponse> getAccountBalance(
            @Parameter(description = "GL account identifier") @PathVariable UUID glAccountId) {
        log.info("Get GL account balance");
        GLAccountBalanceResponse response = glAccountService.getAccountBalance(glAccountId);
        return ResponseEntity.ok(response);
    }
}
