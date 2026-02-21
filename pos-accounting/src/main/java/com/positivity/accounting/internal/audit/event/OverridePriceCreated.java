package com.positivity.accounting.internal.audit.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.positivity.accounting.internal.enums.AccountingIntent;
import com.positivity.accounting.internal.enums.AccountingStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Event emitted when a price override is created.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OverridePriceCreated {

    private UUID auditId;
    private UUID orderId;
    private UUID lineItemId;
    private BigDecimal originalPrice;
    private BigDecimal adjustedPrice;
    private String actorId;
    private String actorRole;
    private String authorizationLevel;
    private String policyVersion;
    private String reason;
    private Instant timestamp;

    @Builder.Default
    private AccountingIntent accountingIntent = AccountingIntent.REVENUE_ADJUSTMENT;

    @Builder.Default
    private AccountingStatus accountingStatus = AccountingStatus.PENDING_POSTING;

    private String expectedAccountingOutcome;
    private UUID sourceEventId;
    private String sourceDocumentId;
}
