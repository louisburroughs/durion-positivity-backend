package com.positivity.accounting.internal.audit.event;

import com.positivity.accounting.internal.audit.entity.AccountingIntent;
import com.positivity.accounting.internal.audit.entity.AccountingStatus;
import com.positivity.accounting.internal.audit.entity.CancellationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Event emitted when a cancellation is created.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CancellationCreated {
    
    private UUID auditId;
    private UUID orderId;
    private UUID invoiceId;
    private CancellationType cancellationType;
    private UUID actorId;
    private String actorRole;
    private String authorizationLevel;
    private String policyVersion;
    private String reason;
    private Instant timestamp;
    
    private AccountingIntent accountingIntent;
    
    @Builder.Default
    private AccountingStatus accountingStatus = AccountingStatus.PENDING_POSTING;
    
    private String beforeSnapshot;
    private String afterSnapshot;
    private String partialPaymentInfo;
    private String expectedAccountingOutcome;
    private UUID sourceEventId;
    private String sourceDocumentId;
}
