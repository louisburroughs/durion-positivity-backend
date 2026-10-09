package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.PettyExpenseCategoryCreateRequest;
import com.positivity.accounting.internal.dto.PettyExpenseCategoryDeactivateRequest;
import com.positivity.accounting.internal.dto.PettyExpenseCategoryListResponse;
import com.positivity.accounting.internal.dto.PettyExpenseCategoryRemapRequest;
import com.positivity.accounting.internal.dto.PettyExpenseCategoryResponse;
import com.positivity.accounting.internal.dto.PettyExpenseCategoryTaxRecoveryRequest;
import com.positivity.accounting.internal.dto.PettyExpenseCategoryTaxRecoveryResponse;
import com.positivity.accounting.internal.dto.PettyExpenseCategoryUpdateRequest;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.InputTaxRecoveryService;
import com.positivity.accounting.internal.service.PettyExpenseCategoryService;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.jspecify.annotations.NonNull;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Petty-expense categories (#2511; SPEC-accounting-workspace §4.6, §7.1 "Petty-expense categories"; AW18): the
 * categories a cashier picks for drawer cash paid out, stored as {@code REGISTER_CASH_MOVEMENT} mapping keys
 * {@code PETTY_EXPENSE_<code>} under the mapping-key and gl-mapping permissions §4.6 names. Every write takes
 * its actor from the security context and a justification of at least 10 characters.
 */
@RestController
@RequestMapping("/v1/accounting/petty-expense-categories")
@Validated
@Tag(name = "Accounting Petty-Expense Categories", description = "What drawer cash may be spent on, and where it posts")
public class PettyExpenseCategoryController {

    private static final String JUSTIFICATION_RULE =
            "justification (at least 10 characters) and requestId (a replay returns the first result with 200; the"
                    + " same requestId with another body is 409 IDEMPOTENCY_CONFLICT)";

    private final PettyExpenseCategoryService service;
    private final InputTaxRecoveryService taxRecovery;

    public PettyExpenseCategoryController(
            @NonNull PettyExpenseCategoryService service, @NonNull InputTaxRecoveryService taxRecovery) {
        this.service = service;
        this.taxRecovery = taxRecovery;
    }

    @GetMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:mapping-key:view"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.MAPPING_KEY_VIEW + "')")
    @Operation(
            operationId = "listPettyExpenseCategories",
            summary = "List Petty-Expense Categories",
            description = """
                    Lists the tenant's petty-expense categories, active and inactive, in code order: per \
                    category its permanent code, label, examples, status, the expense account it posts to today \
                    (id, number, name, effective from), any account that takes over on a later date, and the \
                    change history (date, actor and actorName, the actor's display name or null when not known, \
                    change old to new, justification).
                    Use this tool to show what cashiers may spend drawer cash on and where each category posts; \
                    do not use listMappingKeysByCategory, which shows keys without their labels, accounts or \
                    history.
                    Preconditions: caller holds accounting:mapping-key:view; the tenant's accounting time zone is \
                    set (422 ACCOUNTING_TIME_ZONE_UNSET otherwise). Read-only and idempotent.
                    Required inputs: none.
                    Emits an ACCOUNTING_PETTY_EXPENSE_CATEGORY_LIST event and returns 200 with the categories.
                    """,
            tags = {"Accounting Petty-Expense Categories"})
    @ApiResponse(responseCode = "200", description = "The categories")
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:mapping-key:view",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_PETTY_EXPENSE_CATEGORY_LIST", apiVersion = "1")
    public ResponseEntity<PettyExpenseCategoryListResponse> list() {
        return ResponseEntity.ok(service.list());
    }

    @PostMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:mapping-key:create", "accounting:gl-mapping:create"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.MAPPING_KEY_CREATE + "') and hasAuthority('"
            + AccountingPermissions.GL_MAPPING_CREATE + "')")
    @Operation(
            operationId = "createPettyExpenseCategory",
            summary = "Create Petty-Expense Category",
            description = """
                    Adds a petty-expense category: its REGISTER_CASH_MOVEMENT mapping key PETTY_EXPENSE_<code> \
                    and a GL mapping to the chosen expense account, effective today in the tenant's accounting \
                    time zone.
                    Use this tool when cashiers need a new category of drawer spending; do not use \
                    createMappingKey or createGLMapping, which refuse petty-expense keys (422 \
                    PETTY_EXPENSE_CATEGORY_MANAGED).
                    Preconditions: caller holds accounting:mapping-key:create and accounting:gl-mapping:create; \
                    the code is new (409 PETTY_EXPENSE_CATEGORY_EXISTS) and is not OTHER (422 \
                    PETTY_EXPENSE_CATEGORY_NOT_ALLOWED: there is no "Other"); glAccountId is an active EXPENSE \
                    account (422 PETTY_EXPENSE_ACCOUNT_NOT_ELIGIBLE).
                    Required inputs: code (permanent, [A-Z0-9_]{1,40}), label, glAccountId, \
                    """ + JUSTIFICATION_RULE + """
                    ; examples is optional.
                    Emits an ACCOUNTING_PETTY_EXPENSE_CATEGORY_CREATE event, writes a history row naming the \
                    caller, queues accounting.petty-expense-category.changed, and returns 201 with the category, \
                    each history row's actorName resolved now (null when not known, a replayed requestId included).
                    """,
            tags = {"Accounting Petty-Expense Categories"})
    @ApiResponse(responseCode = "201", description = "The category was created")
    @ApiResponse(responseCode = "200", description = "A replayed requestId")
    @ApiResponse(
            responseCode = "400",
            description = "Missing or invalid field (VALIDATION_ERROR)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:mapping-key:create or accounting:gl-mapping:create",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "PETTY_EXPENSE_CATEGORY_EXISTS or IDEMPOTENCY_CONFLICT",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "PETTY_EXPENSE_CATEGORY_NOT_ALLOWED or PETTY_EXPENSE_ACCOUNT_NOT_ELIGIBLE",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_PETTY_EXPENSE_CATEGORY_CREATE", apiVersion = "1")
    public ResponseEntity<PettyExpenseCategoryResponse> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description = "The new category's code, label, account and why",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = PettyExpenseCategoryCreateRequest.class),
                                            examples = @ExampleObject(name = "Tire disposal", value = """
                                                    {"code":"TIRE_DISPOSAL","label":"Tire disposal","examples":"Scrap tire hauler fees","glAccountId":"019a0000-0000-7000-8000-00000000c000","justification":"Cashiers pay the scrap hauler","requestId":"019a0000-0000-7000-8000-000000000103"}
                                                    """)))
                    @RequestBody
                    PettyExpenseCategoryCreateRequest request) {
        PettyExpenseCategoryResponse response = service.create(request);
        return ResponseEntity.status(response.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(response);
    }

    @PutMapping("/{code}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:mapping-key:edit"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.MAPPING_KEY_EDIT + "')")
    @Operation(
            operationId = "updatePettyExpenseCategory",
            summary = "Relabel Petty-Expense Category",
            description = """
                    Changes a petty-expense category's label and examples; the code never changes, and the \
                    mapping key's description follows the label.
                    Use this tool to make a category clearer to cashiers; do not use updateMappingKey, which \
                    refuses petty-expense keys (422 PETTY_EXPENSE_CATEGORY_MANAGED) and would rename the key.
                    Preconditions: caller holds accounting:mapping-key:edit; the category exists (404); a \
                    version, when sent, is the current one (409 VERSION_CONFLICT).
                    Required inputs: code (path), label, \
                    """ + JUSTIFICATION_RULE + """
                    ; examples and version are optional.
                    Emits an ACCOUNTING_PETTY_EXPENSE_CATEGORY_UPDATE event, writes a history row naming the \
                    caller, queues accounting.petty-expense-category.changed, and returns 200 with the category, \
                    each history row's actorName resolved now (null when not known, a replayed requestId included).
                    """,
            tags = {"Accounting Petty-Expense Categories"})
    @ApiResponse(responseCode = "200", description = "The category was relabelled, or a replayed requestId")
    @ApiResponse(
            responseCode = "400",
            description = "Missing or invalid field (VALIDATION_ERROR)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:mapping-key:edit",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No such category (PETTY_EXPENSE_CATEGORY_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "VERSION_CONFLICT or IDEMPOTENCY_CONFLICT",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_PETTY_EXPENSE_CATEGORY_UPDATE", apiVersion = "1")
    public ResponseEntity<PettyExpenseCategoryResponse> update(
            @Parameter(description = "Permanent category code", example = "STAFF_MEALS") @PathVariable String code,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description = "The new label and examples, and why",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = PettyExpenseCategoryUpdateRequest.class),
                                            examples = @ExampleObject(name = "Clearer label", value = """
                                                    {"label":"Staff meals and coffee","version":0,"justification":"Cashiers asked for a clearer label","requestId":"019a0000-0000-7000-8000-000000000104"}
                                                    """)))
                    @RequestBody
                    PettyExpenseCategoryUpdateRequest request) {
        return ResponseEntity.ok(service.update(code, request));
    }

    @PostMapping("/{code}/deactivate")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:mapping-key:deactivate"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.MAPPING_KEY_DEACTIVATE + "')")
    @Operation(
            operationId = "deactivatePettyExpenseCategory",
            summary = "Deactivate Petty-Expense Category",
            description = """
                    Deactivates a petty-expense category so cashiers can no longer pick it. Its mapping is kept, \
                    so movements recorded before deactivation still post at close (never retroactive). There is \
                    no delete and no reactivation.
                    Use this tool to retire a category; do not use deactivateMappingKey, which refuses \
                    petty-expense keys (422 PETTY_EXPENSE_CATEGORY_MANAGED).
                    Preconditions: caller holds accounting:mapping-key:deactivate; the category exists (404) and \
                    is active (409 PETTY_EXPENSE_CATEGORY_INACTIVE).
                    Required inputs: code (path), \
                    """ + JUSTIFICATION_RULE + """
                    .
                    Emits an ACCOUNTING_PETTY_EXPENSE_CATEGORY_DEACTIVATE event, writes a history row naming the \
                    caller, queues accounting.petty-expense-category.changed, and returns 200 with the category, \
                    each history row's actorName resolved now (null when not known, a replayed requestId included).
                    """,
            tags = {"Accounting Petty-Expense Categories"})
    @ApiResponse(responseCode = "200", description = "The category is inactive, or a replayed requestId")
    @ApiResponse(
            responseCode = "400",
            description = "Missing or invalid field (VALIDATION_ERROR)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:mapping-key:deactivate",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No such category (PETTY_EXPENSE_CATEGORY_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "PETTY_EXPENSE_CATEGORY_INACTIVE or IDEMPOTENCY_CONFLICT",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_PETTY_EXPENSE_CATEGORY_DEACTIVATE", apiVersion = "1")
    public ResponseEntity<PettyExpenseCategoryResponse> deactivate(
            @Parameter(description = "Permanent category code", example = "STAFF_MEALS") @PathVariable String code,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description = "Why the category is retired",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema =
                                                    @Schema(
                                                            implementation =
                                                                    PettyExpenseCategoryDeactivateRequest.class),
                                            examples = @ExampleObject(name = "Retire fuel", value = """
                                                    {"justification":"We stopped buying fuel in cash","requestId":"019a0000-0000-7000-8000-000000000105"}
                                                    """)))
                    @RequestBody
                    PettyExpenseCategoryDeactivateRequest request) {
        return ResponseEntity.ok(service.deactivate(code, request));
    }

    @PostMapping("/{code}/account")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:gl-mapping:create"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.GL_MAPPING_CREATE + "')")
    @Operation(
            operationId = "remapPettyExpenseCategory",
            summary = "Change Petty-Expense Category Account",
            description = """
                    Moves a petty-expense category to another expense account from a date: the current mapping \
                    ends the day before effectiveFrom and the new one starts on it, so a posting dated earlier \
                    still reaches the old account.
                    Use this tool when a category should post somewhere else from now on; do not use \
                    createGLMapping, which cannot bind a mapping to a category and key.
                    Preconditions: caller holds accounting:gl-mapping:create; the category exists (404); no \
                    mapping of the category starts on or after effectiveFrom (400 \
                    PETTY_EXPENSE_MAPPING_OVERLAP, the non-overlap rule); glAccountId is an active EXPENSE account \
                    (422 PETTY_EXPENSE_ACCOUNT_NOT_ELIGIBLE); effectiveFrom is today or later in the tenant's \
                    accounting calendar, never retroactive (422 PETTY_EXPENSE_ACCOUNT_CHANGE_BACKDATED).
                    Required inputs: code (path), glAccountId, effectiveFrom, \
                    """ + JUSTIFICATION_RULE + """
                    .
                    Emits an ACCOUNTING_PETTY_EXPENSE_CATEGORY_REMAP event, writes a history row naming the \
                    caller, queues accounting.petty-expense-category.changed, and returns 200 with the category, \
                    each history row's actorName resolved now (null when not known, a replayed requestId included).
                    """,
            tags = {"Accounting Petty-Expense Categories"})
    @ApiResponse(responseCode = "200", description = "The new account is mapped, or a replayed requestId")
    @ApiResponse(
            responseCode = "400",
            description = "VALIDATION_ERROR or PETTY_EXPENSE_MAPPING_OVERLAP",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:gl-mapping:create",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No such category (PETTY_EXPENSE_CATEGORY_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "IDEMPOTENCY_CONFLICT",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "PETTY_EXPENSE_ACCOUNT_NOT_ELIGIBLE, PETTY_EXPENSE_ACCOUNT_CHANGE_BACKDATED or"
                    + " ACCOUNTING_TIME_ZONE_UNSET",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_PETTY_EXPENSE_CATEGORY_REMAP", apiVersion = "1")
    public ResponseEntity<PettyExpenseCategoryResponse> remap(
            @Parameter(description = "Permanent category code", example = "SHOP_SUPPLIES") @PathVariable String code,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description = "The new account, the date it applies from, and why",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = PettyExpenseCategoryRemapRequest.class),
                                            examples = @ExampleObject(name = "Own account from November", value = """
                                                    {"glAccountId":"019a0000-0000-7000-8000-00000000c000","effectiveFrom":"2026-11-01","justification":"Supplies get their own account from November","requestId":"019a0000-0000-7000-8000-000000000106"}
                                                    """)))
                    @RequestBody
                    PettyExpenseCategoryRemapRequest request) {
        return ResponseEntity.ok(service.remap(code, request));
    }

    @PutMapping("/{code}/tax-recovery")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:mapping-key:edit"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.MAPPING_KEY_EDIT + "')")
    @Operation(
            operationId = "setPettyExpenseCategoryTaxRecovery",
            summary = "Set Petty-Expense Category Tax Recovery",
            description = """
                    Sets whether the tax stated on a petty-expense category's receipts is recovered, and which \
                    share, from now on; a movement already recorded keeps the share in force when it was recorded.
                    Use this tool when the accountant decides how much of a category's stated tax may be claimed; \
                    do not use updatePettyExpenseCategory, which changes only the label and examples.
                    Preconditions: caller holds accounting:mapping-key:edit; the category exists (404); recovery \
                    under at least one regime is on today (422 INPUT_TAX_RECOVERY_NOT_ENABLED); version is the one \
                    last read, 0 for a category never set (409 OPTIMISTIC_LOCK); the tax service answers whether a \
                    regime's recovery is on (503 SERVICE_UNAVAILABLE with Retry-After when it cannot).
                    Required inputs: code (path), taxRecoverable, recoverablePercent in (0, 100] when recoverable \
                    and absent otherwise, version, \
                    """ + JUSTIFICATION_RULE + """
                    .
                    Emits an ACCOUNTING_PETTY_CATEGORY_TAX_RECOVERY_UPDATE event, writes a history row naming the \
                    caller and role, queues accounting.petty-expense-category.changed with the new values, and \
                    returns 200 with the setting.
                    """,
            tags = {"Accounting Petty-Expense Categories"})
    @ApiResponse(responseCode = "200", description = "The recovery was set, or a replayed requestId")
    @ApiResponse(
            responseCode = "400",
            description = "Missing or invalid field (VALIDATION_ERROR)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks accounting:mapping-key:edit",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No such category (PETTY_EXPENSE_CATEGORY_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "OPTIMISTIC_LOCK or IDEMPOTENCY_CONFLICT",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "INPUT_TAX_RECOVERY_NOT_ENABLED or ACCOUNTING_TIME_ZONE_UNSET",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "503",
            description = "SERVICE_UNAVAILABLE: whether a regime's recovery is on cannot be determined now (the tax"
                    + " service did not answer); nothing was changed, retry after Retry-After seconds",
            headers =
                    @io.swagger.v3.oas.annotations.headers.Header(
                            name = "Retry-After",
                            description = "Seconds to wait before retrying",
                            schema = @Schema(type = "integer", example = "30")),
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_PETTY_CATEGORY_TAX_RECOVERY_UPDATE", apiVersion = "1")
    public ResponseEntity<PettyExpenseCategoryTaxRecoveryResponse> setTaxRecovery(
            @Parameter(description = "Permanent category code", example = "STAFF_MEALS") @PathVariable String code,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description = "Whether the category's stated tax is recovered, which share, and why",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema =
                                                    @Schema(
                                                            implementation =
                                                                    PettyExpenseCategoryTaxRecoveryRequest.class),
                                            examples = @ExampleObject(name = "Half recoverable", value = """
                                                    {"taxRecoverable":true,"recoverablePercent":50.00,"version":0,"justification":"Meals are half recoverable under the regime","requestId":"019a0000-0000-7000-8000-000000000107"}
                                                    """)))
                    @RequestBody
                    PettyExpenseCategoryTaxRecoveryRequest request) {
        return ResponseEntity.ok(taxRecovery.setCategoryRecovery(code, request));
    }
}
