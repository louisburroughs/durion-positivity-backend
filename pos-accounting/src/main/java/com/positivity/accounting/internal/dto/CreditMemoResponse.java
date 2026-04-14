package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.enums.CreditMemoStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;

/**
 * Response containing Credit Memo details.
 */
@Data
public class CreditMemoResponse {

    private UUID creditMemoId;
    private UUID originalInvoiceId;
    private UUID customerId;
    private BigDecimal creditAmount;
    private BigDecimal taxAmountReversed;
    private BigDecimal totalAmount;
    private String reasonCode;
    private String justificationNote;
    private CreditMemoStatus status;
    private Instant creationTimestamp;
    private Instant postedTimestamp;
    private String createdByUserId;
    private Boolean priorPeriodAdjustment;
    private String originalPeriodId;
    private String currency;
    private BigDecimal invoiceBalanceAfter;
}
