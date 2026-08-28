package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.dto.SettlementLineResponse;
import com.positivity.accounting.internal.dto.SettlementManualMatchRequest;
import com.positivity.accounting.internal.dto.SettlementWriteOffRequest;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.SettlementReconciliationService;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
 *      "domains/accounting/plan-odoo-parity-pos-accounting.md">Odoo Parity Plan
 *      -
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
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_VIEW + "')")
    @EmitEvent(id = "ACCOUNTING_SETTLEMENT_LINES_LIST", apiVersion = "1")
    @Operation(
            operationId = "listSettlementLines",
            summary = "List Settlement Lines",
            description = """
                    Lists the lines of one processor settlement (payout), optionally filtered to only the \
                    UNMATCHED lines awaiting review; matched lines are already posted, while unmatched lines \
                    park their gross in the settlement suspense account until resolved.
                    Use this tool to triage a settlement before matchSettlementLine or \
                    writeOffSettlementLine; do not use those mutation tools just to inspect state.
                    Preconditions: none; an unknown settlementId returns an empty list rather than an error.
                    Required inputs: settlementId (the provider payout id string) as a path parameter; \
                    unmatchedOnly defaults to false.
                    Emits an ACCOUNTING_SETTLEMENT_LINES_LIST audit event; no state changes.
                    Returns 200 with an empty list when the settlement is unknown or fully filtered out.
                    """,
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
                    @NonNull
                    String settlementId,
            @Parameter(description = "When true, return only UNMATCHED lines", example = "true")
                    @RequestParam(name = "unmatchedOnly", defaultValue = "false")
                    boolean unmatchedOnly) {
        if (log.isInfoEnabled()) {
            log.info(
                    "List settlement lines for {} (unmatchedOnly={})",
                    sanitizeForLog(settlementId),
                    sanitizeForLog(unmatchedOnly));
        }
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
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_ADJUST + "')")
    @EmitEvent(id = "ACCOUNTING_SETTLEMENT_LINE_MATCH", apiVersion = "1")
    @Operation(
            operationId = "matchSettlementLine",
            summary = "Manually Match Settlement Line",
            description = """
                    Manually matches an UNMATCHED settlement line to an AR receivable payment, posting a \
                    reclass entry (debit Settlement Suspense, credit Undeposited Funds) that clears the \
                    line's gross out of suspense.
                    Use this tool when the automatic gross match could not identify the payment; do not use \
                    writeOffSettlementLine, which is only for small unidentifiable residuals, and note that \
                    AP vendor matching is not supported in v1.
                    Preconditions: the line must exist and be UNMATCHED, its parent settlement must already \
                    be POSTED, and the receivable payment must exist.
                    Required inputs: lineId (UUID) as a path parameter and receivablePaymentId (UUID) in the \
                    body.
                    Emits an ACCOUNTING_SETTLEMENT_LINE_MATCH event and posts the reclass journal entry.
                    Returns 404 SETTLEMENT_LINE_NOT_FOUND or RECEIVABLE_PAYMENT_NOT_FOUND when either record \
                    is missing, 409 SETTLEMENT_LINE_NOT_UNMATCHED or SETTLEMENT_NOT_POSTED for state \
                    conflicts, and 422 PERIOD_CLOSED or PERIOD_HARD_LOCKED when the reclass entry is dated \
                    into a locked period.
                    """,
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
            @Parameter(description = "Settlement line id", required = true) @PathVariable @NonNull UUID lineId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Receivable payment to match the unmatched settlement line against.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Match to payment",
                                                            value =
                                                                    "{\"receivablePaymentId\":\"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b\"}")))
                    @Valid
                    @RequestBody
                    @NonNull
                    SettlementManualMatchRequest request) {
        if (log.isInfoEnabled()) {
            log.info(
                    "Manual match settlement line {} to receivable payment {}",
                    sanitizeForLog(lineId),
                    sanitizeForLog(request.getReceivablePaymentId()));
        }
        SettlementLineResponse response = SettlementLineResponse.from(
                settlementReconciliationService.manualMatchToReceivable(lineId, request.getReceivablePaymentId()));
        return ResponseEntity.ok(response);
    }

    @PostMapping("/lines/{lineId}/write-off")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"accounting:reconciliation:adjust"})
    @PreAuthorize("hasAuthority('" + AccountingPermissions.RECONCILIATION_ADJUST + "')")
    @EmitEvent(id = "ACCOUNTING_SETTLEMENT_LINE_WRITE_OFF", apiVersion = "1")
    @Operation(
            operationId = "writeOffSettlementLine",
            summary = "Write Off Small Settlement Line",
            description = """
                    Writes off an UNMATCHED settlement line whose gross is at or below the configured \
                    write-off threshold (default 25.00), posting a reversible adjustment entry (debit \
                    Settlement Suspense, credit Settlement Adjustment) rather than a silent status flip.
                    Use this tool only for genuinely unidentifiable small residuals; do not use it above the \
                    threshold, where matchSettlementLine or escalation is the only path.
                    Preconditions: the line must exist and be UNMATCHED, its parent settlement must be \
                    POSTED, and the line's absolute gross must not exceed the threshold.
                    Required inputs: lineId (UUID) as a path parameter and a non-blank audited reason (max \
                    500 chars).
                    Emits an ACCOUNTING_SETTLEMENT_LINE_WRITE_OFF event and posts the adjustment entry \
                    through the accounting-period gate.
                    Returns 422 WRITE_OFF_THRESHOLD_EXCEEDED when the absolute gross exceeds the threshold \
                    or PERIOD_CLOSED / PERIOD_HARD_LOCKED for locked periods, 409 \
                    SETTLEMENT_LINE_NOT_UNMATCHED or SETTLEMENT_NOT_POSTED for state conflicts, and 404 \
                    SETTLEMENT_LINE_NOT_FOUND when the line is missing.
                    """,
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
            @Parameter(description = "Settlement line id", required = true) @PathVariable @NonNull UUID lineId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Audited reason for writing off the small unmatched residual.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Write off rounding residual",
                                                            value =
                                                                    "{\"reason\":\"Processor rounding residual, no matching payment\"}")))
                    @Valid
                    @RequestBody
                    @NonNull
                    SettlementWriteOffRequest request) {
        if (log.isInfoEnabled()) {
            log.info("Write off settlement line {}", sanitizeForLog(lineId));
        }
        SettlementLineResponse response =
                SettlementLineResponse.from(settlementReconciliationService.writeOffLine(lineId, request.getReason()));
        return ResponseEntity.ok(response);
    }

    private static String sanitizeForLog(@Nullable Object value) {
        if (value == null) {
            return "null";
        }
        return value.toString().replace('\n', '_').replace('\r', '_');
    }
}
