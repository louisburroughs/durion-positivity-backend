package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.entity.ProcessorSettlementLine;
import com.positivity.accounting.internal.enums.MatchedPaymentType;
import com.positivity.accounting.internal.enums.SettlementLineMatchStatus;
import com.positivity.accounting.internal.enums.SettlementLineType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for a processor settlement line in the reconciliation review workflow
 * (Story F1c, issue #963, decision D-14). Exposes the persisted line without
 * leaking the JPA entity to the API surface.
 *
 * @see <a href=
 *      "domains/accounting/plan-odoo-parity-pos-accounting.md">Odoo Parity Plan -
 *      Story F1c</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Processor settlement line with its reconciliation match status")
public class SettlementLineResponse {

    @Schema(description = "Settlement line UUID", example = "01936e5e-7890-7a3d-8b6e-4d5678901234")
    private UUID lineId;

    @Schema(description = "Owning settlement id (provider payout id)", example = "stl_1Mx3k2eZvKYlo2C0")
    private String settlementId;

    @Schema(description = "Provider's own line reference", example = "txn_3Mx3k2eZvKYlo2C0")
    private String providerLineRef;

    @Schema(description = "Settlement line type", example = "CHARGE")
    private SettlementLineType lineType;

    @Schema(description = "Line gross amount (matched on gross, decision D-11)", example = "1000.0000")
    private BigDecimal grossAmount;

    @Schema(description = "Line fee amount; null for netted/header-only providers", example = "30.0000")
    private BigDecimal feeAmount;

    @Schema(description = "Line net amount; null for netted/header-only providers", example = "970.0000")
    private BigDecimal netAmount;

    @Schema(description = "Platform correlation token echoed by the processor (decision D-11)")
    private String paymentReference;

    @Schema(description = "Reconciliation match status", example = "UNMATCHED")
    private SettlementLineMatchStatus matchStatus;

    @Schema(description = "Id of the matched AR/AP payment; null while UNMATCHED")
    private UUID matchedPaymentId;

    @Schema(description = "Whether the matched payment is AR (receivable) or AP (vendor)")
    private MatchedPaymentType matchedPaymentType;

    @Schema(description = "Recorded reason when the line was written off")
    private String writeoffReason;

    @Schema(description = "Journal entry id of the reversible write-off posting")
    private UUID writeoffJournalEntryId;

    @Schema(description = "When the line was first ingested")
    private Instant createdAt;

    @Schema(description = "When the line was last updated")
    private Instant updatedAt;

    /** Map a persisted settlement line to its API response. */
    public static SettlementLineResponse from(ProcessorSettlementLine line) {
        return SettlementLineResponse.builder()
                .lineId(line.getLineId())
                .settlementId(line.getSettlementId())
                .providerLineRef(line.getProviderLineRef())
                .lineType(line.getLineType())
                .grossAmount(line.getGrossAmount())
                .feeAmount(line.getFeeAmount())
                .netAmount(line.getNetAmount())
                .paymentReference(line.getPaymentReference())
                .matchStatus(line.getMatchStatus())
                .matchedPaymentId(line.getMatchedPaymentId())
                .matchedPaymentType(line.getMatchedPaymentType())
                .writeoffReason(line.getWriteoffReason())
                .writeoffJournalEntryId(line.getWriteoffJournalEntryId())
                .createdAt(line.getCreatedAt())
                .updatedAt(line.getUpdatedAt())
                .build();
    }
}
