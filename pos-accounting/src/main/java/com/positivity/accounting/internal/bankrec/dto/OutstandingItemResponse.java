package com.positivity.accounting.internal.bankrec.dto;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliationOutstandingItem;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemKind;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemSide;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** An outstanding (timing) item (SPEC §3.6; story S4, #2303). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Outstanding (timing) item: explains a difference, never posts")
public class OutstandingItemResponse {

    private UUID outstandingItemId;
    private UUID glAccountId;

    @Schema(description = "LEDGER or BANK")
    private OutstandingItemSide side;

    private UUID glLineId;
    private UUID bankTransactionId;

    @Schema(example = "DEPOSIT_IN_TRANSIT")
    private OutstandingItemKind itemKind;

    @Schema(description = "Signed amount copied from the source line", example = "500.0000")
    private BigDecimal signedAmount;

    @Schema(description = "Date of the source line", example = "2026-09-30")
    private LocalDate itemDate;

    @Schema(description = "Days from itemDate to the date the item is read at", example = "12")
    private long ageDays;

    @Schema(description = "OPEN, CLEARED, CLEARED_IN_GAP, VOIDED or RELEASED")
    private OutstandingItemStatus status;

    @Schema(description = "The day the item left OPEN other than by release")
    private LocalDate closedOn;

    private UUID registeredInReconciliationId;
    private String registeredBy;
    private Instant registeredAt;
    private String justification;
    private UUID clearedInReconciliationId;
    private UUID clearedByMatchId;
    private Instant clearedAt;
    private String clearedBy;
    private String clearanceJustification;
    private Instant releasedAt;
    private String releasedBy;
    private String releaseReason;

    @Schema(description = "The reconciliation that last reaffirmed an aged OTHER_LEDGER_TIMING item")
    private UUID lastReaffirmedInReconciliationId;

    private String lastReaffirmedBy;
    private Instant lastReaffirmedAt;
    private String reaffirmJustification;

    /** The item, aged at {@code asOf}. */
    public static OutstandingItemResponse from(BankReconciliationOutstandingItem i, LocalDate asOf) {
        return OutstandingItemResponse.builder()
                .outstandingItemId(i.getOutstandingItemId())
                .glAccountId(i.getGlAccountId())
                .side(i.getSide())
                .glLineId(i.getGlLineId())
                .bankTransactionId(i.getBankTransactionId())
                .itemKind(i.getItemKind())
                .signedAmount(i.getSignedAmount())
                .itemDate(i.getItemDate())
                .ageDays(Math.max(0, ChronoUnit.DAYS.between(i.getItemDate(), asOf)))
                .status(i.getStatus())
                .closedOn(i.getClosedOn())
                .registeredInReconciliationId(i.getRegisteredInReconciliationId())
                .registeredBy(i.getRegisteredBy())
                .registeredAt(i.getRegisteredAt())
                .justification(i.getJustification())
                .clearedInReconciliationId(i.getClearedInReconciliationId())
                .clearedByMatchId(i.getClearedByMatchId())
                .clearedAt(i.getClearedAt())
                .clearedBy(i.getClearedBy())
                .clearanceJustification(i.getClearanceJustification())
                .releasedAt(i.getReleasedAt())
                .releasedBy(i.getReleasedBy())
                .releaseReason(i.getReleaseReason())
                .lastReaffirmedInReconciliationId(i.getLastReaffirmedInReconciliationId())
                .lastReaffirmedBy(i.getLastReaffirmedBy())
                .lastReaffirmedAt(i.getLastReaffirmedAt())
                .reaffirmJustification(i.getReaffirmJustification())
                .build();
    }
}
