package com.positivity.accounting.internal.audit.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.positivity.accounting.internal.enums.AccountingIntent;
import com.positivity.accounting.internal.enums.AccountingStatus;
import com.positivity.accounting.internal.enums.RefundType;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Event emitted when a refund is created.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefundCreated {

    private UUID auditId;
    private UUID invoiceId;
    private UUID paymentId;
    private RefundType refundType;
    private BigDecimal refundAmount;
    private UUID actorId;
    private String actorRole;
    private String authorizationLevel;
    private String policyVersion;
    private String reason;
    private Instant timestamp;

    private AccountingIntent accountingIntent;

    @Builder.Default
    private AccountingStatus accountingStatus = AccountingStatus.PENDING_POSTING;

    private String linkedSourceIds;
    private String expectedAccountingOutcome;
    private UUID sourceEventId;
    private String sourceDocumentId;
}
