package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.AutomaticWorkItemsResponse;
import com.positivity.accounting.internal.dto.WorkItemsResponse;
import com.positivity.accounting.internal.enums.WorkItemArea;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.WorkItemAutomaticService;
import com.positivity.accounting.internal.service.WorkItemService;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The to-do list and what was done automatically (CAP:550 S19, #2515; SPEC-accounting-workspace §7.1 "Work items",
 * "Done automatically"). Both reads answer 403 to a caller holding none of the permissions an item type needs; the
 * services then show each item only to the holders of its own permission.
 */
@RestController
@RequestMapping("/v1/accounting/work-items")
@RequiredArgsConstructor
@Tag(name = "Work Items", description = "What needs a person in accounting, and what was done automatically")
public class WorkItemController {

    /** The permissions of the item types: a caller with none of them has nothing to see. */
    private static final String ITEM_AUTHORITIES = "hasAnyAuthority('"
            + AccountingPermissions.AP_APPROVE
            + "','" + AccountingPermissions.AP_APPROVE_OVER_LIMIT
            + "','" + AccountingPermissions.AP_PAY
            + "','" + AccountingPermissions.AP_VIEW
            + "','" + AccountingPermissions.PAYMENT_APPLY
            + "','" + AccountingPermissions.DEPOSIT_CREATE
            + "','" + AccountingPermissions.RECONCILIATION_ADJUST
            + "','" + AccountingPermissions.RECONCILIATION_APPROVE
            + "','" + AccountingPermissions.REPORTING_VIEW_FINANCIAL_STATEMENTS
            + "','" + AccountingPermissions.JE_POST
            + "')";

    private final WorkItemService workItemService;
    private final WorkItemAutomaticService automaticService;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:approve"})
    @PreAuthorize(ITEM_AUTHORITIES)
    @Operation(
            operationId = "listWorkItems",
            summary = "List Work Items",
            description = """
                    Lists what needs a person in accounting, oldest first: vendor bills to check, pick a match \
                    for, send, approve or pay; customer payments to match; unpaid walk-in sales; vendor payments \
                    that did not post; drawer cash to deposit; unmatched bank lines; and reconciliations waiting \
                    for approval. Each item names its business references, why it is listed, its amount and \
                    date, the actions the caller may take (an action a rule blocks is shown with allowed false \
                    and its blockedReason code) and the entry an action would post. Items are built on read and \
                    shown only for the permissions the caller holds.
                    Use this tool to answer "what needs me today"; use listAutomaticWorkItems for what the system \
                    already did, and do not use it to find a bill or payment by name.
                    Preconditions: the caller holds at least one of the permissions the item types need.
                    Required inputs: none; area narrows the list to BILLS, PAYMENTS, BANK or CASH, cursor is the \
                    nextCursor of the previous page for the same area, limit is 1 to 200 and 50 when omitted.
                    Emits an ACCOUNTING_WORK_ITEMS_LIST event and changes no state; countsByArea counts every \
                    area the caller may see, before the area filter and paging.
                    Returns 200 with the page, 400 VALIDATION_ERROR for a limit outside 1 to 200, 400 \
                    WORK_ITEMS_CURSOR_INVALID for a cursor that is malformed or issued for another area \
                    (start again from the first page), and 403 when the caller holds none of the permissions.
                    """,
            tags = {"Work Items"})
    @ApiResponse(
            responseCode = "200",
            description = "A page of work items, oldest first, with the caller's counts per area",
            content = @Content(schema = @Schema(implementation = WorkItemsResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "limit is outside 1 to 200 (VALIDATION_ERROR), or the cursor is not valid for this list"
                    + " (WORK_ITEMS_CURSOR_INVALID)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller holds none of the permissions the work item types need",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_WORK_ITEMS_LIST", apiVersion = "1")
    public ResponseEntity<WorkItemsResponse> listWorkItems(
            @Parameter(description = "Only this area's items: BILLS, PAYMENTS, BANK or CASH", example = "BILLS")
                    @RequestParam(name = "area", required = false)
                    WorkItemArea area,
            @Parameter(description = "The nextCursor of the previous page, issued for the same area")
                    @RequestParam(name = "cursor", required = false)
                    String cursor,
            @Parameter(description = "Page size, from 1 to 200", example = "50")
                    @RequestParam(name = "limit", required = false)
                    Integer limit) {
        return ResponseEntity.ok(workItemService.list(area, cursor, limit));
    }

    @GetMapping(value = "/automatic", produces = MediaType.APPLICATION_JSON_VALUE)
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:ap:approve"})
    @PreAuthorize(ITEM_AUTHORITIES)
    @Operation(
            operationId = "listAutomaticWorkItems",
            summary = "List Work Done Automatically",
            description = """
                    Lists what the system did without a person since a time, oldest first: customer payments \
                    applied to their invoices automatically (with UNDO for a holder of \
                    accounting:payment:reverse, and listed without it once reversed), settlement lines matched \
                    automatically and vendor bills approved by the system approver. Bank reconciliation \
                    proposals are never listed; they stay to-do items.
                    Use this tool to show "done automatically" and to undo a payment application; use \
                    listWorkItems for what still needs a person.
                    Preconditions: the caller holds at least one of the permissions the work item types need.
                    Required inputs: none; since is an ISO instant or a date read as the start of that day in the \
                    tenant's accounting time zone, the start of today when omitted, at most 31 days back and not \
                    in the future; cursor is the nextCursor of the previous page for the same since; limit is 1 \
                    to 200 and 50 when omitted.
                    Emits an ACCOUNTING_WORK_ITEMS_AUTOMATIC_LIST event and changes no state.
                    Returns 200 with the page, 400 VALIDATION_ERROR for a since or limit out of range, 400 \
                    WORK_ITEMS_CURSOR_INVALID for a cursor that is malformed or issued for another \
                    since, and 403 when the caller holds none of the permissions.
                    """,
            tags = {"Work Items"})
    @ApiResponse(
            responseCode = "200",
            description = "A page of what was done automatically, oldest first",
            content = @Content(schema = @Schema(implementation = AutomaticWorkItemsResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "since or limit is out of range (VALIDATION_ERROR), or the cursor is not valid for this"
                    + " list (WORK_ITEMS_CURSOR_INVALID)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller holds none of the permissions the work item types need",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "ACCOUNTING_WORK_ITEMS_AUTOMATIC_LIST", apiVersion = "1")
    public ResponseEntity<AutomaticWorkItemsResponse> listAutomaticWorkItems(
            @Parameter(
                            description = "An ISO instant, or a date read in the tenant's accounting time zone;"
                                    + " the start of today when omitted",
                            example = "2026-10-17")
                    @RequestParam(name = "since", required = false)
                    String since,
            @Parameter(description = "The nextCursor of the previous page, issued for the same since")
                    @RequestParam(name = "cursor", required = false)
                    String cursor,
            @Parameter(description = "Page size, from 1 to 200", example = "50")
                    @RequestParam(name = "limit", required = false)
                    Integer limit) {
        return ResponseEntity.ok(automaticService.list(since, cursor, limit));
    }
}
