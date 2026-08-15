package com.positivity.invoice.internal.controller;

import com.positivity.invoice.internal.dto.ElevateRequest;
import com.positivity.invoice.internal.dto.ElevateResponse;
import com.positivity.invoice.internal.exception.ElevationDeniedException;
import com.positivity.invoice.internal.security.InvoicePermissions;
import com.positivity.invoice.internal.service.ElevationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manager-approval elevation endpoint.
 *
 * <p>Allows an actor who holds {@code invoice:finalize} but not
 * {@code invoice:finalize:override} to obtain a short-lived elevation token by naming an
 * approving manager (employee number). The token is then supplied as the
 * {@code managerApprovalCode} on the finalize call.
 */
@RestController
@RequestMapping("/v1/billing/auth")
@SecurityRequirement(
        name = "bearerAuth",
        scopes = {"invoice:finalize"})
@Tag(name = "Billing Authorization", description = "Manager-approval elevation for controlled finalization")
@PreAuthorize("hasAuthority('" + InvoicePermissions.FINALIZE + "')")
public class ElevationController {

    private static final Logger log = LoggerFactory.getLogger(ElevationController.class);

    private final ElevationService elevationService;

    public ElevationController(@NonNull ElevationService elevationService) {
        this.elevationService = elevationService;
    }

    @PostMapping("/elevate")
    @Operation(
            operationId = "elevateManagerApproval",
            summary = "Mint Manager-Approval Elevation Token",
            description = """
                    Mints a short-lived elevation token scoped to one invoice after verifying that the named manager \
                    is an ACTIVE employee holding the invoice:finalize:override authority.
                    Use this tool when an actor with invoice:finalize but not invoice:finalize:override needs a \
                    managerApprovalCode for finalizeInvoice or revertInvoice; do not use finalizeInvoice directly \
                    without a token when the invoice total exceeds the 500.00 service-advisor cap.
                    Preconditions: the manager's employee number must resolve to an ACTIVE person in the local \
                    employee replica and that person must hold invoice:finalize:override.
                    Required inputs: managerEmployeeNumber and invoiceId (UUID); the token is bound to that invoice \
                    only and expires after five minutes by default (invoice.elevation.token-ttl-seconds).
                    No events are emitted; the token is signed and stateless, and the grant is audit-logged with the \
                    approving manager's person id.
                    Returns 200 with the token and its expiry, and 401 with an empty body when the employee number \
                    is unknown or inactive or the person lacks the override authority.
                    """)
    @ApiResponse(responseCode = "200", description = "Elevation token minted")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @ApiResponse(responseCode = "401", description = "Manager approval denied")
    public ResponseEntity<ElevateResponse> elevate(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Manager identification and the invoice the elevation token will authorize.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Elevation for one invoice", value = """
                                                                    {"managerEmployeeNumber":"EMP-0001",
                                                                     "invoiceId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a20"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    ElevateRequest request) {
        ElevateResponse response = elevationService.elevate(request.getManagerEmployeeNumber(), request.getInvoiceId());
        return ResponseEntity.ok(response);
    }

    @ExceptionHandler(ElevationDeniedException.class)
    public ResponseEntity<Void> handleDenied(ElevationDeniedException ex) {
        log.info("Elevation denied");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
}
