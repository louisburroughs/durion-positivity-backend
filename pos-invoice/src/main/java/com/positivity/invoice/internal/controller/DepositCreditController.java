package com.positivity.invoice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.invoice.internal.dto.CreateDepositRequest;
import com.positivity.invoice.internal.dto.DepositCreditResponse;
import com.positivity.invoice.internal.enums.DepositSourceType;
import com.positivity.invoice.service.DepositCreditService;
import com.positivity.invoice.service.model.CreateDepositCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deposit / down-payment credit endpoints (odoo-parity story E4, issue #1085, spec R7.4). Registers
 * deposits taken at pos-order checkout, exposes the credit and its application-audit trail, and
 * refunds a credit when its source is cancelled.
 */
@RestController
@RequestMapping("/v1/invoices/deposits")
@RequiredArgsConstructor
@Tag(name = "Deposit Credits", description = "Deposit / down-payment credits and their application to settlements")
@PreAuthorize("hasAuthority('invoice:manage')")
public class DepositCreditController {

    private final DepositCreditService depositCreditService;

    @Operation(
            summary = "Register a deposit credit taken by a sales order",
            tags = {"Deposit Credits"})
    @PostMapping
    @EmitEvent(id = "INVOICE_DEPOSIT_CREATE", apiVersion = "1")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<DepositCreditResponse> createDeposit(@Valid @RequestBody CreateDepositRequest request) {
        DepositCreditResponse response =
                DepositCreditResponse.from(depositCreditService.createDeposit(new CreateDepositCommand(
                        request.getOrderId(),
                        request.getSourceType(),
                        request.getSourceId(),
                        request.getPartyId(),
                        request.getAmount(),
                        request.getCurrencyCode())));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
            summary = "Get a deposit credit by id",
            tags = {"Deposit Credits"})
    @GetMapping("/{depositCreditId}")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<DepositCreditResponse> getDeposit(@PathVariable UUID depositCreditId) {
        return ResponseEntity.ok(DepositCreditResponse.from(depositCreditService.getDeposit(depositCreditId)));
    }

    @Operation(
            summary = "List deposit credits held against a source document",
            tags = {"Deposit Credits"})
    @GetMapping
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<List<DepositCreditResponse>> listBySource(
            @RequestParam String sourceType, @RequestParam UUID sourceId) {
        DepositSourceType type = DepositSourceType.valueOf(sourceType.trim().toUpperCase(Locale.ROOT));
        return ResponseEntity.ok(depositCreditService.listBySource(type, sourceId).stream()
                .map(DepositCreditResponse::from)
                .toList());
    }

    @Operation(
            summary = "Refund a deposit credit's remaining balance",
            description = "Used when the deposit's source is cancelled after the deposit was taken (spec R7.4).",
            tags = {"Deposit Credits"})
    @PostMapping("/{depositCreditId}/refund")
    @EmitEvent(id = "INVOICE_DEPOSIT_REFUND", apiVersion = "1")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<DepositCreditResponse> refundDeposit(
            @PathVariable UUID depositCreditId, @RequestParam(name = "reason", required = false) String reason) {
        depositCreditService.refundDeposit(depositCreditId, reason == null ? "source cancelled" : reason);
        return ResponseEntity.ok(DepositCreditResponse.from(depositCreditService.getDeposit(depositCreditId)));
    }
}
