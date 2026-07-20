package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.SettlementLineResponse;
import com.positivity.accounting.internal.dto.SettlementManualMatchRequest;
import com.positivity.accounting.internal.dto.SettlementWriteOffRequest;
import com.positivity.accounting.service.SettlementReconciliationService;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller for the processor settlement reconciliation review workflow
 * (Story F1c, issue #963, decision D-14). Settlement ingestion is event-driven
 * ({@code SettlementReportedV1} → {@code SettlementEventsListener}); this
 * controller exposes only the human review actions on lines the matcher left
 * {@code UNMATCHED}: list, manual match to a receivable payment, and small
 * write-off (reversible JE below the configured threshold).
 *
 * @see <a href=
 *      "domains/accounting/plan-odoo-parity-pos-accounting.md">Odoo Parity Plan -
 *      Story F1c</a>
 */
@RestController
@RequestMapping("/v1/accounting/settlements")
@Tag(
        name = "Settlement Reconciliation",
        description = "Processor settlement reconciliation review: list settlement lines, manually match an"
                + " unmatched line to a receivable payment, and write off small unmatched lines.")
@RequiredArgsConstructor
@Validated
public class SettlementReconciliationController {

    private static final Logger log = LoggerFactory.getLogger(SettlementReconciliationController.class);

    private final SettlementReconciliationService settlementReconciliationService;

    @GetMapping("/{settlementId}/lines")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:view"})
    @PreAuthorize("hasAuthority('accounting:reconciliation:view')")
    @EmitEvent(id = "ACCOUNTING_SETTLEMENT_LINES_LIST", apiVersion = "1")
    @Operation(
            summary = "List settlement lines",
            operationId = "listSettlementLines",
            description = "Lists the lines of one processor settlement (payout), optionally filtered to only the"
                    + " UNMATCHED lines awaiting review. Use this tool to triage a settlement before manually"
                    + " matching or writing off its unmatched lines. Matched lines are already posted; unmatched"
                    + " lines park their gross in the settlement suspense account until resolved (decision D-13)."
                    + " No preconditions and no side effects; an unknown settlementId returns an empty list.",
            tags = {"Settlement Reconciliation"})
    @ApiResponse(
            responseCode = "200",
            description = "Settlement lines listed",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = SettlementLineResponse.class))))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks the accounting:reconciliation:view permission",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<SettlementLineResponse>> listSettlementLines(
            @Parameter(
                            description = "Provider settlement (payout) id",
                            required = true,
                            example = "stl_1Mx3k2eZvKYlo2C0")
                    @PathVariable
                    String settlementId,
            @Parameter(description = "When true, return only UNMATCHED lines", example = "true")
                    @RequestParam(name = "unmatchedOnly", defaultValue = "false")
                    boolean unmatchedOnly) {
        log.info("List settlement lines for {} (unmatchedOnly={})", settlementId, unmatchedOnly);
        List<SettlementLineResponse> response =
                settlementReconciliationService.listLines(settlementId, unmatchedOnly).stream()
                        .map(SettlementLineResponse::from)
                        .toList();
        return ResponseEntity.ok(response);
    }

    @PostMapping("/lines/{lineId}/match")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:adjust"})
    @PreAuthorize("hasAuthority('accounting:reconciliation:adjust')")
    @EmitEvent(id = "ACCOUNTING_SETTLEMENT_LINE_MATCH", apiVersion = "1")
    @Operation(
            summary = "Manually match a settlement line",
            operationId = "matchSettlementLine",
            description = "Manually matches an UNMATCHED settlement line to an AR receivable payment, posting a"
                    + " reclass entry (Dr Settlement Suspense / Cr Undeposited Funds) that clears the line's gross"
                    + " out of suspense. Use this tool when the automatic gross match could not identify the"
                    + " payment. AP (vendor) matching is not supported in v1."
                    + " Preconditions: the line must exist and be UNMATCHED, its parent settlement must already"
                    + " be POSTED, and the receivable payment must exist. Returns 404 if the line or payment is"
                    + " not found (SETTLEMENT_LINE_NOT_FOUND / RECEIVABLE_PAYMENT_NOT_FOUND), 409 if the line is"
                    + " no longer UNMATCHED (SETTLEMENT_LINE_NOT_UNMATCHED) or the settlement has not yet posted"
                    + " (SETTLEMENT_NOT_POSTED), and 422 if the reclass entry is dated into a locked accounting"
                    + " period (PERIOD_CLOSED / PERIOD_HARD_LOCKED). Emits ACCOUNTING_SETTLEMENT_LINE_MATCH.",
            tags = {"Settlement Reconciliation"})
    @ApiResponse(
            responseCode = "200",
            description = "Line matched; the updated line is returned",
            content = @Content(schema = @Schema(implementation = SettlementLineResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "receivablePaymentId is missing (ARGUMENT_NOT_VALID)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks the accounting:reconciliation:adjust permission",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Settlement line or receivable payment not found"
                    + " (SETTLEMENT_LINE_NOT_FOUND / RECEIVABLE_PAYMENT_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Settlement line is not UNMATCHED (SETTLEMENT_LINE_NOT_UNMATCHED), or the settlement"
                    + " has not yet posted (SETTLEMENT_NOT_POSTED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Reclass entry is dated into a CLOSED or hard-locked accounting period"
                    + " (PERIOD_CLOSED / PERIOD_HARD_LOCKED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<SettlementLineResponse> matchSettlementLine(
            @Parameter(description = "Settlement line id", required = true) @PathVariable UUID lineId,
            @Valid @RequestBody SettlementManualMatchRequest request) {
        log.info("Manual match settlement line {} to receivable payment {}", lineId, request.getReceivablePaymentId());
        SettlementLineResponse response = SettlementLineResponse.from(
                settlementReconciliationService.manualMatchToReceivable(lineId, request.getReceivablePaymentId()));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/lines/{lineId}/write-off")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:adjust"})
    @PreAuthorize("hasAuthority('accounting:reconciliation:adjust')")
    @EmitEvent(id = "ACCOUNTING_SETTLEMENT_LINE_WRITE_OFF", apiVersion = "1")
    @Operation(
            summary = "Write off a small unmatched settlement line",
            operationId = "writeOffSettlementLine",
            description = "Writes off an UNMATCHED settlement line whose gross is at or below the configured"
                    + " reconciliation write-off threshold (default $25.00, decision D-14), posting a reversible"
                    + " adjustment entry (Dr Settlement Suspense / Cr Settlement Adjustment) — never a silent"
                    + " status flip. Use this tool only for genuinely unidentifiable small residuals; above the"
                    + " threshold there is no self-service write-off — manually match or escalate."
                    + " Preconditions: the line must exist and be UNMATCHED, its gross must not exceed the"
                    + " threshold (compared on absolute value), and a non-blank reason (max 500 chars) is required"
                    + " and audited. Respects accounting period locks (Story B2). Emits"
                    + " ACCOUNTING_SETTLEMENT_LINE_WRITE_OFF. Returns 422 when the absolute gross exceeds the"
                    + " threshold (WRITE_OFF_THRESHOLD_EXCEEDED) or the adjustment entry is dated into a locked"
                    + " accounting period (PERIOD_CLOSED / PERIOD_HARD_LOCKED), and 409 when the line is no longer"
                    + " UNMATCHED (SETTLEMENT_LINE_NOT_UNMATCHED) or the settlement has not yet posted"
                    + " (SETTLEMENT_NOT_POSTED).",
            tags = {"Settlement Reconciliation"})
    @ApiResponse(
            responseCode = "200",
            description = "Line written off; the updated line is returned",
            content = @Content(schema = @Schema(implementation = SettlementLineResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "reason is missing, blank, or over 500 characters (ARGUMENT_NOT_VALID)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks the accounting:reconciliation:adjust permission",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Settlement line not found (SETTLEMENT_LINE_NOT_FOUND)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Settlement line is not UNMATCHED (SETTLEMENT_LINE_NOT_UNMATCHED), or the settlement"
                    + " has not yet posted (SETTLEMENT_NOT_POSTED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Line absolute gross exceeds the write-off threshold (WRITE_OFF_THRESHOLD_EXCEEDED);"
                    + " manual match or escalate — or the adjustment entry is dated into a CLOSED or hard-locked"
                    + " accounting period (PERIOD_CLOSED / PERIOD_HARD_LOCKED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<SettlementLineResponse> writeOffSettlementLine(
            @Parameter(description = "Settlement line id", required = true) @PathVariable UUID lineId,
            @Valid @RequestBody SettlementWriteOffRequest request) {
        log.info("Write off settlement line {}", lineId);
        SettlementLineResponse response =
                SettlementLineResponse.from(settlementReconciliationService.writeOffLine(lineId, request.getReason()));
        return ResponseEntity.ok(response);
    }
}
