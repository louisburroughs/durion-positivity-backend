package com.positivity.invoice.internal.controller;

import com.positivity.invoice.internal.dto.ElevateRequest;
import com.positivity.invoice.internal.dto.ElevateResponse;
import com.positivity.invoice.internal.exception.ElevationDeniedException;
import com.positivity.invoice.internal.service.ElevationService;
import io.swagger.v3.oas.annotations.Operation;
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
@PreAuthorize("hasAuthority('invoice:finalize')")
public class ElevationController {

    private static final Logger log = LoggerFactory.getLogger(ElevationController.class);

    private final ElevationService elevationService;

    public ElevationController(@NonNull ElevationService elevationService) {
        this.elevationService = elevationService;
    }

    @PostMapping("/elevate")
    @Operation(
            summary = "Mint a manager-approval elevation token",
            description = "Verifies the named manager (by employee number) holds invoice:finalize:override and mints"
                    + " a short-lived token scoped to the given invoice, returned as the managerApprovalCode for finalize.")
    @ApiResponse(responseCode = "200", description = "Elevation token minted")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @ApiResponse(responseCode = "401", description = "Manager approval denied")
    public ResponseEntity<ElevateResponse> elevate(@Valid @RequestBody @NonNull ElevateRequest request) {
        ElevateResponse response =
                elevationService.elevate(request.getManagerEmployeeNumber(), request.getInvoiceId());
        return ResponseEntity.ok(response);
    }

    @ExceptionHandler(ElevationDeniedException.class)
    public ResponseEntity<Void> handleDenied(ElevationDeniedException ex) {
        log.info("Elevation denied");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
}
