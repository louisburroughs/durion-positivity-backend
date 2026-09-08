package com.positivity.invoice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.invoice.internal.config.InvoiceService;
import com.positivity.invoice.internal.dto.AdjustmentRequest;
import com.positivity.invoice.internal.dto.FinalizationRequest;
import com.positivity.invoice.internal.dto.InvoiceDetailsResponse;
import com.positivity.invoice.internal.dto.RevertRequest;
import com.positivity.invoice.internal.security.InvoicePermissions;
import com.positivity.invoice.internal.service.InvoiceFinalizationService;
import com.positivity.invoice.internal.service.OrderInvoiceService;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.dto.InvoiceCreationRequest;
import com.positivity.shared.dto.InvoiceGenerationResponse;
import com.positivity.shared.dto.OrderInvoiceCreationRequest;
import com.positivity.shared.dto.OrderInvoiceResponse;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/invoices")
@Tag(name = "Invoice", description = "Invoice generation and lifecycle management")
// No class-level @PreAuthorize: every handler names its own permission (#1612).
// RequiredPermissionsOpenApiAutoConfiguration emits the UNION of the class-level and method-level
// guards, while Spring resolves the most specific one — so with a class-level invoice:manage here,
// getInvoice advertised x-required-permissions [invoice:manage, invoice:invoice:view] while
// actually accepting only the second. A discovered OpenAPI operation is gated with OR, so a caller
// holding just invoice:manage would have been offered a read it then 403s on: the exact
// selectable-but-not-callable defect #1606 fixed for facade tools.
// InvoiceReadAuthorityTest asserts every handler here carries an explicit guard, so removing the
// class-level default cannot leave a future method unguarded.
//
// Location scope (ADR-0061 §3, #1872): @PreAuthorize answers "may this caller manage invoices";
// LocationScope.require answers "…at this location". The two create routes are gated here on the
// request's locationId — the shop the invoice is raised at — so a caller whose invoice:manage grant
// is location-scoped cannot bill another shop by naming it in the body. getInvoice is gated in
// InvoiceServiceImpl on the stored invoice's location, after the 404, so the create gate cannot be
// bypassed by reading the invoice back and ids cannot be probed through the 403.
public class InvoiceController {

    private static final String LOCATION_SCOPE_DENIED_DESCRIPTION =
            "Caller holds invoice:manage but its location scope does not cover the invoice's location"
                    + " (ApiError.code LOCATION_SCOPE_DENIED, see docs/ERROR_ENVELOPE.md)";

    private static final String VIEW_LOCATION_SCOPE_DENIED_DESCRIPTION =
            "Caller holds invoice:invoice:view but its location scope does not cover the invoice's location"
                    + " (ApiError.code LOCATION_SCOPE_DENIED, see docs/ERROR_ENVELOPE.md)";

    private final InvoiceService invoiceService;
    private final InvoiceFinalizationService invoiceFinalizationService;
    private final OrderInvoiceService orderInvoiceService;

    public InvoiceController(
            @NonNull InvoiceService invoiceService,
            @NonNull InvoiceFinalizationService invoiceFinalizationService,
            @NonNull OrderInvoiceService orderInvoiceService) {
        this.invoiceService = invoiceService;
        this.invoiceFinalizationService = invoiceFinalizationService;
        this.orderInvoiceService = orderInvoiceService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + InvoicePermissions.MANAGE + "')")
    @EmitEvent(id = "INVOICE_CREATE", apiVersion = "1")
    @Operation(operationId = "createInvoice", summary = "Create Invoice Draft from Workorder", description = """
                    Creates a DRAFT invoice from completed workorder data, pricing the supplied line items, \
                    calculating draft tax against the shop location's jurisdiction, and assigning the permanent \
                    invoice number immediately.
                    Use this tool for workorder billing; do not use createInvoiceFromOrder, which fronts a sales \
                    order at counter-sale checkout with order-authoritative totals.
                    Preconditions: the workorder must be complete enough to bill; the call is idempotent on \
                    workorderId — a replay returns the workorder's existing invoice instead of creating a duplicate. \
                    A caller whose invoice:manage grant is location-scoped must have locationId within reach \
                    (ADR-0061); for such a caller an omitted locationId is denied.
                    Required inputs: workorderId (UUID); estimateId, approvalId, locationId, customerId, \
                    idempotencyKey and lineItems (description, quantity, unitPrice, amount, optional type) are \
                    optional, and a missing lineItems list produces an empty zero-subtotal draft.
                    Emits an INVOICE_CREATE event, persists the per-line tax breakdown, and publishes an \
                    invoice-updated notification.
                    Returns 201 with the invoice (existing or new), 400 when workorderId is missing, and 403 \
                    LOCATION_SCOPE_DENIED when the caller's location scope does not cover locationId.
                    """)
    @ApiResponse(responseCode = "201", description = "Invoice created")
    @ApiResponse(
            responseCode = "403",
            description = LOCATION_SCOPE_DENIED_DESCRIPTION,
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"invoice:manage"})
    public ResponseEntity<InvoiceGenerationResponse> createInvoice(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Workorder billing data the invoice draft is built from.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Workorder invoice draft", value = """
                                                                    {"workorderId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a30",
                                                                     "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a31",
                                                                     "customerId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a32",
                                                                     "idempotencyKey":"inv-create-wo-1234",
                                                                     "lineItems":[{"description":"Brake pad replacement",
                                                                       "quantity":2.0,"unitPrice":45.00,
                                                                       "amount":90.00,"type":"LABOR"}]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    InvoiceCreationRequest request) {
        requireInvoiceLocation(request.getLocationId());
        InvoiceGenerationResponse response = invoiceService.createInvoice(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/from-order")
    @PreAuthorize("hasAuthority('" + InvoicePermissions.MANAGE + "')")
    @EmitEvent(id = "INVOICE_CREATE_FROM_ORDER", apiVersion = "1")
    @Operation(operationId = "createInvoiceFromOrder", summary = "Create Invoice from Sales Order", description = """
                    Creates the DRAFT invoice fronting a sales order at counter-sale checkout, recording the order's \
                    already-priced subtotal, tax and total verbatim — pos-order and pos-tax are authoritative, so \
                    nothing is re-priced here.
                    Use this tool at order checkout; do not use createInvoice, which builds and prices a draft from \
                    workorder data.
                    Preconditions: the order must carry final totals and at least one line; the call is idempotent \
                    on orderId, and when workorderId is set an existing workorder invoice is returned for tender \
                    instead of creating a duplicate. A caller whose invoice:manage grant is location-scoped must \
                    have locationId within reach (ADR-0061); for such a caller an omitted locationId is denied.
                    Required inputs: orderId (UUID), subtotal, taxAmount, totalAmount (non-negative) and lines; \
                    customerId, locationId and the deposit fields are optional, but depositSourceType and \
                    depositSourceId become mandatory when depositAmount is set.
                    Emits an INVOICE_CREATE_FROM_ORDER event; a deposit take also registers a deposit credit \
                    (idempotent on orderId), and a workorder settlement draws down available deposit credits, \
                    reported as depositApplied.
                    Returns 201 when a new invoice is created, 200 when an existing invoice is returned (orderId \
                    replay or workorder dedupe), 400 when totals are missing or negative, lines are empty, or \
                    deposit fields are inconsistent, and 403 LOCATION_SCOPE_DENIED when the caller's location \
                    scope does not cover locationId.
                    """)
    @ApiResponse(responseCode = "201", description = "Invoice created")
    @ApiResponse(responseCode = "200", description = "Existing invoice returned (replay or workorder dedupe)")
    @ApiResponse(
            responseCode = "403",
            description = LOCATION_SCOPE_DENIED_DESCRIPTION,
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"invoice:manage"})
    public ResponseEntity<OrderInvoiceResponse> createInvoiceFromOrder(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Sales order snapshot — authoritative totals and sold lines — the invoice records.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Counter sale", value = """
                                                                    {"orderId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a40",
                                                                     "customerId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a41",
                                                                     "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a42",
                                                                     "subtotal":120.00,"taxAmount":9.60,"totalAmount":129.60,
                                                                     "lines":[{"orderLineId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a43",
                                                                       "description":"Brake pad set","quantity":2,
                                                                       "unitPrice":45.00,"amount":90.00,
                                                                       "taxAmount":7.20,"type":"PART"}]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    OrderInvoiceCreationRequest request) {
        requireInvoiceLocation(request.getLocationId());
        OrderInvoiceResponse response = orderInvoiceService.createInvoiceForOrder(request);
        HttpStatus status = response.isExisting() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    @PostMapping("/{invoiceId}/cancel")
    @PreAuthorize("hasAuthority('" + InvoicePermissions.MANAGE + "')")
    @EmitEvent(id = "INVOICE_CANCEL", apiVersion = "1")
    @Operation(operationId = "cancelInvoice", summary = "Cancel a Draft Invoice", description = """
                    Cancels a DRAFT invoice terminally before any money has moved, the invoice-side effect of an \
                    order void.
                    Use this tool when an order is voided before tender; do not use revertInvoice, which returns a \
                    FINALIZED invoice to DRAFT, and do not use it when payments exist — reverse those first through \
                    voidPayment or refundPayment (order cancellation saga).
                    Preconditions: the invoice must still be DRAFT and must carry no AUTHORIZED or CAPTURED payment \
                    intent; cancelling an already CANCELLED invoice is an idempotent no-op.
                    Required inputs: invoiceId (UUID) as a path parameter; there is no request body.
                    Emits an INVOICE_CANCEL event and sets the invoice status to CANCELLED.
                    Returns 200 when cancelled or already cancelled, 409 when the invoice has left DRAFT or carries \
                    an authorized/captured payment, and 404 when the invoice does not exist.
                    """)
    @ApiResponse(responseCode = "200", description = "Invoice cancelled (or already cancelled)")
    @ApiResponse(responseCode = "409", description = "Invoice not cancellable in its current state")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"invoice:manage"})
    public ResponseEntity<OrderInvoiceResponse> cancelInvoice(@PathVariable @NonNull UUID invoiceId) {
        return ResponseEntity.ok(orderInvoiceService.cancelInvoice(invoiceId));
    }

    @GetMapping("/{invoiceId}")
    @EmitEvent(id = "INVOICE_GET", apiVersion = "1")
    @Operation(operationId = "getInvoice", summary = "Get Invoice Details", description = """
                    Returns the full invoice detail — status, line items, adjustments, totals, tax breakdown, due \
                    date and the resolved workorder number.
                    Use this tool when the invoiceId is already known; use searchInvoices instead when locating an \
                    invoice by number, customer name or workorder number.
                    Preconditions: the invoice must exist. A caller whose invoice:invoice:view grant is \
                    location-scoped must have the invoice's location within reach (ADR-0061).
                    Required inputs: invoiceId (UUID) as a path parameter; there is no request body.
                    Emits an INVOICE_GET audit event; no state changes — this is a read-only projection.
                    Returns 404 when no invoice exists for the supplied id, and 403 LOCATION_SCOPE_DENIED when \
                    the invoice exists but its location is outside the caller's scope.
                    """)
    @ApiResponse(responseCode = "200", description = "Invoice found")
    @ApiResponse(
            responseCode = "403",
            description = VIEW_LOCATION_SCOPE_DENIED_DESCRIPTION,
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Invoice not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + InvoicePermissions.VIEW + "')")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"invoice:invoice:view"})
    public ResponseEntity<InvoiceDetailsResponse> getInvoice(@PathVariable @NonNull UUID invoiceId) {
        return ResponseEntity.ok(invoiceService.getInvoice(invoiceId));
    }

    @PostMapping("/{invoiceId}/adjustments")
    @PreAuthorize("hasAuthority('" + InvoicePermissions.MANAGE + "')")
    @EmitEvent(id = "INVOICE_ADJUSTMENT_APPLY", apiVersion = "1")
    @Operation(operationId = "applyInvoiceAdjustment", summary = "Apply Adjustment to Draft Invoice", description = """
                    Applies a monetary adjustment (DISCOUNT, FEE, CORRECTION, or WARRANTY credit) to a DRAFT invoice \
                    and recalculates its tax and total.
                    Use this tool while the invoice is still DRAFT; do not use it after finalization — tax and \
                    totals freeze there, so revertInvoice must return the invoice to DRAFT first.
                    Preconditions: the invoice must exist and be in DRAFT status; a retry supplying the same type \
                    and externalReference replays idempotently, returning the invoice without double-crediting.
                    Required inputs: type, amount (greater than zero), reason, and authorizedBy; externalReference \
                    is optional and correlates the adjustment to an external record such as a warranty settlement.
                    Emits an INVOICE_ADJUSTMENT_APPLY event, replaces the persisted per-line tax breakdown, and \
                    publishes an invoice-updated notification.
                    Returns 200 with the recalculated invoice, 404 when the invoice does not exist, 409 when the \
                    invoice has left DRAFT, 400 when the amount is not positive (type, reason and authorizedBy are \
                    enforced by request validation), and 422 when the adjustment would drive the invoice total \
                    negative (a credit memo is required instead).
                    """)
    @ApiResponse(responseCode = "200", description = "Adjustment applied")
    @ApiResponse(
            responseCode = "422",
            description = "The adjustment would drive the invoice total negative",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"invoice:manage"})
    public ResponseEntity<InvoiceDetailsResponse> applyAdjustment(
            @PathVariable @NonNull UUID invoiceId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Adjustment to add to the draft invoice, with its business justification.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Warranty credit", value = """
                                                                    {"type":"WARRANTY","amount":25.00,
                                                                     "reason":"Warranty settlement credit",
                                                                     "authorizedBy":"jdoe",
                                                                     "externalReference":"WC-2026-000042"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    AdjustmentRequest request) {
        return ResponseEntity.ok(invoiceService.applyAdjustment(invoiceId, request));
    }

    @PostMapping("/{invoiceId}/finalize")
    @EmitEvent(id = "INVOICE_FINALIZED", apiVersion = "1")
    @Operation(operationId = "finalizeInvoice", summary = "Finalize a Draft Invoice", description = """
                    Transitions an invoice from DRAFT to FINALIZED: runs the committable tax calculation, freezes \
                    tax, totals, payment terms and due date, then commits the provider tax document.
                    Use this tool when the sale is ready to issue; do not use revertInvoice, which undoes a \
                    finalization, and mint the managerApprovalCode with elevateManagerApproval when one is needed.
                    Preconditions: the invoice must be DRAFT with tax already calculated; callers without \
                    invoice:finalize:override (or a manager/admin role) need a valid elevation token as \
                    managerApprovalCode when the stored total exceeds 500.00.
                    Required inputs: invoiceId (UUID) as a path parameter; managerApprovalCode and overrideReason in \
                    the body are optional below the cap and for override holders.
                    Emits an INVOICE_FINALIZED event and publishes the invoice.invoice.updated fact (status \
                    FINALIZED); pos-accounting consumes it and posts the revenue journal entry, and the invoice \
                    becomes POSTED asynchronously when the accounting.invoice.gl-posted fact from pos-accounting arrives. The \
                    tax commit tolerates a provider outage by recording PENDING_COMMIT in pos-tax for the re-commit \
                    job, and is skipped entirely when nothing is taxable.
                    Returns 200 with the finalized invoice, 404 when the invoice does not exist, 409 when the \
                    invoice is not DRAFT or tax has not been calculated, and 403 with MANAGER_APPROVAL_REQUIRED or \
                    MANAGER_APPROVAL_INVALID when a required managerApprovalCode is missing, invalid, or expired \
                    (a step-up credential the caller lacks; nextAction points at elevateManagerApproval).
                    """)
    @ApiResponse(responseCode = "200", description = "Invoice finalized")
    @ApiResponse(
            responseCode = "403",
            description = "MANAGER_APPROVAL_REQUIRED or MANAGER_APPROVAL_INVALID: a required managerApprovalCode is "
                    + "missing, invalid, or expired",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"invoice:finalize"})
    @PreAuthorize("hasAuthority('" + InvoicePermissions.FINALIZE + "')")
    public ResponseEntity<InvoiceDetailsResponse> finalizeInvoice(
            @PathVariable @NonNull UUID invoiceId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Optional manager-approval material for finalizations above the amount cap.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Finalize with manager approval",
                                                            value = """
                                                                    {"managerApprovalCode":"MGR-ELEV-TOKEN-abc123",
                                                                     "overrideReason":"Customer pre-approved by branch manager"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    FinalizationRequest request) {
        return ResponseEntity.ok(invoiceFinalizationService.completeInvoice(invoiceId, request));
    }

    @PostMapping("/{invoiceId}/revert")
    @EmitEvent(id = "INVOICE_DRAFT_REVERT", apiVersion = "1")
    @Operation(operationId = "revertInvoice", summary = "Revert Finalized Invoice to Draft", description = """
                    Reverts a FINALIZED invoice back to DRAFT within 24 hours of finalization, before GL posting has \
                    made it immutable, and voids the provider tax document committed at finalization.
                    Use this tool to correct a wrongly finalized invoice; do not use cancelInvoice, which \
                    terminally cancels a DRAFT invoice on the order-void path.
                    Preconditions: the invoice must be FINALIZED (POSTED is immutable), less than 24 hours must have \
                    elapsed since finalizedAt, and callers without invoice:finalize:override (or a manager/admin \
                    role) must supply a valid elevation token from elevateManagerApproval as managerApprovalCode.
                    Required inputs: invoiceId (UUID) as a path parameter plus managerApprovalCode and a reason in \
                    the body; the reverting actor and approving manager are captured for audit.
                    Emits an INVOICE_DRAFT_REVERT event, publishes an invoice-updated notification, and issues a tax \
                    void toward pos-tax for the reverted document.
                    Returns 200 with the DRAFT invoice, 404 when the invoice does not exist, 409 when it is POSTED, \
                    not FINALIZED, or the 24-hour window has expired, and 403 with MANAGER_APPROVAL_INVALID when the \
                    approval code is invalid or expired (a step-up credential the server considers insufficient; a \
                    blank approval code is rejected as a 400 request-shape error before this check).
                    """)
    @ApiResponse(responseCode = "200", description = "Invoice reverted to DRAFT")
    @ApiResponse(
            responseCode = "403",
            description = "MANAGER_APPROVAL_INVALID: the approval code is invalid or expired",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"invoice:finalize"})
    @PreAuthorize("hasAuthority('" + InvoicePermissions.FINALIZE + "')")
    public ResponseEntity<InvoiceDetailsResponse> revertInvoice(
            @PathVariable @NonNull UUID invoiceId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Manager approval and business reason authorizing the reversion.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Revert with approval", value = """
                                                                    {"managerApprovalCode":"MGR-ELEV-TOKEN-abc123",
                                                                     "reason":"Incorrect line item billed"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    RevertRequest request) {
        return ResponseEntity.ok(
                invoiceFinalizationService.revert(invoiceId, request.getManagerApprovalCode(), request.getReason()));
    }

    /**
     * Location-scope gate for the two create routes (ADR-0061 §3, #1872): the request's
     * {@code locationId} names the shop the invoice is raised at. Both request types leave it
     * optional; a scoped caller cannot cover "no location", so an omitted id is denied for them
     * (the same fail-closed rule pos-workorder applies to a locationless workorder), while an
     * unscoped or pre-rollout caller is unchanged.
     */
    private static void requireInvoiceLocation(@Nullable UUID locationId) {
        LocationScope scope = SecurityContextHelper.locationScope();
        scope.require(InvoicePermissions.MANAGE, locationId == null ? "" : locationId.toString());
    }
}
