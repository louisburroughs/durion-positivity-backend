package com.positivity.catalog.internal.dto;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

@Data
public class GuardrailPolicyUpsertRequestDto {
    private UUID scopeId;
    private BigDecimal minMarginPercent;
    private BigDecimal maxDiscountPercent;
    private BigDecimal autoApprovalThresholdPercent;
}
