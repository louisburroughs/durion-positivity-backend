package com.positivity.accounting.internal.controller;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.positivity.accounting.internal.dto.APPaymentResponse;
import com.positivity.accounting.internal.dto.ExecuteAPPaymentRequest;
import com.positivity.accounting.internal.dto.VendorBillSummaryResponse;
import com.positivity.accounting.service.APPaymentService;
import com.positivity.events.EmitEvent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;

/**
 * REST controller for AP (Accounts Payable) payment operations.
 * 
 * <p>
 * Endpoints:
 * <ul>
 * <li>POST /v1/accounting/ap/payments - Execute vendor payment</li>
 * <li>GET /v1/accounting/ap/payments/{paymentId} - Get payment details</li>
 * <li>GET /v1/accounting/ap/bills - List eligible vendor bills</li>
 * </ul>
 * 
 * @see APPaymentService
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/128">Issue
 *      #128</a>
 */
@Slf4j
@RestController
@RequestMapping("/v1/accounting/ap")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "AP Payments", description = "Accounts Payable vendor payment operations")
@RequiredArgsConstructor
@Validated
public class APPaymentController {

        private final APPaymentService apPaymentService;

        @PostMapping("/payments")
        @EmitEvent(id = "AP_PAYMENT_EXECUTE", apiVersion = "1")
        @Operation(summary = "Execute vendor payment", description = "Execute a vendor payment with optional explicit allocations to bills. "
                        +
                        "Idempotent using paymentRef: same ref + same payload returns existing payment; " +
                        "same ref + different payload yields 409 conflict.")
        @ApiResponse(responseCode = "200", description = "Idempotent replay: existing payment returned")
        @ApiResponse(responseCode = "201", description = "Payment executed successfully (new payment created)")
        @ApiResponse(responseCode = "400", description = "Validation error: negative amounts, invalid bills, etc.")
        @ApiResponse(responseCode = "409", description = "Conflict: paymentRef exists with different payload")
        @ApiResponse(responseCode = "500", description = "Payment gateway failure")
        @PreAuthorize("hasAuthority('accounting:ap:pay')")
        public @NonNull ResponseEntity<APPaymentResponse> executePayment(
                        @Valid @RequestBody @NonNull ExecuteAPPaymentRequest request, Authentication authentication) {

                String currentUser = authentication != null ? authentication.getName() : "system";
                log.info("Executing payment for vendor {} with paymentRef {}", request.getVendorId(),
                                request.getPaymentRef());

                // Check if payment already exists for idempotency
                Optional<APPaymentResponse> existing = apPaymentService.getPaymentByRef(request.getPaymentRef());
                if (existing.isPresent()) {
                        // Idempotent replay: validate and return existing payment with 200 OK
                        log.info("Idempotent replay for paymentRef {}", request.getPaymentRef());
                        APPaymentResponse response = apPaymentService.executePayment(request, currentUser);
                        return ResponseEntity.ok(response);
                }

                // New payment: return 201 Created
                APPaymentResponse response = apPaymentService.executePayment(request, currentUser);
                return ResponseEntity.status(HttpStatus.CREATED).body(response);
        }

        @GetMapping("/payments/{paymentId}")
        @Operation(summary = "Get payment details", description = "Retrieve AP payment details including allocations and GL posting status.")
        @ApiResponse(responseCode = "200", description = "Payment found")
        @ApiResponse(responseCode = "404", description = "Payment not found")
        @PreAuthorize("hasAuthority('accounting:ap:view')")
        public @NonNull ResponseEntity<APPaymentResponse> getPayment(
                        @PathVariable @Parameter(description = "Payment UUID", example = "01936e5c-7890-7a3d-8b6e-2b3456789012") @NonNull UUID paymentId) {

                return apPaymentService.getPaymentById(paymentId)
                                .map(ResponseEntity::ok)
                                .orElse(ResponseEntity.notFound().build());
        }

        @GetMapping("/payments/by-ref/{paymentRef}")
        @Operation(summary = "Get payment by reference", description = "Retrieve AP payment details by paymentRef (idempotency key).")
        @ApiResponse(responseCode = "200", description = "Payment found")
        @ApiResponse(responseCode = "404", description = "Payment not found")
        @PreAuthorize("hasAuthority('accounting:ap:view')")
        public @NonNull ResponseEntity<APPaymentResponse> getPaymentByRef(
                        @PathVariable
                        @Parameter(description = "Payment reference (idempotency key)", example = "01936e5c-7890-7a3d-8b6e-2b3456789012")
                        @NotBlank
                        @Size(min = 1, max = 100, message = "Payment reference must be 1-100 characters")
                        @Pattern(regexp = "^[^\\r\\n]+$", message = "Payment reference must not contain newline characters")
                        @NonNull String paymentRef) {

                return apPaymentService.getPaymentByRef(paymentRef)
                                .map(ResponseEntity::ok)
                                .orElse(ResponseEntity.notFound().build());
        }

        @GetMapping("/bills")
        @Operation(summary = "List eligible vendor bills", description = "Get eligible vendor bills for payment (status = APPROVED). Bills are ordered by due date (oldest first, nulls last), then bill date, then bill ID.")
        @ApiResponse(responseCode = "200", description = "Bills retrieved successfully")

        @ApiResponse(responseCode = "400", description = "Invalid vendor ID")
        @PreAuthorize("hasAuthority('accounting:ap:view')")
        public @NonNull ResponseEntity<List<VendorBillSummaryResponse>> listBills(
                        @RequestParam @Parameter(description = "Vendor UUID", example = "01936e5b-4567-7a3d-8b6e-1a2345678901", required = true) @NonNull UUID vendorId) {

                List<VendorBillSummaryResponse> bills = apPaymentService.listEligibleBills(vendorId);
                return ResponseEntity.ok(bills);
        }
}
