package com.positivity.catalog.internal.dto;

import com.positivity.catalog.internal.enums.ChangeSourceType;
import com.positivity.catalog.internal.enums.CostType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Item cost audit event")
public class ItemCostAuditDto {

    private UUID auditId;

    private UUID itemId;

    private Instant timestamp;

    private CostType costTypeChanged;

    private BigDecimal oldValue;

    private BigDecimal newValue;

    private ChangeSourceType changeSourceType;

    private String changeSourceId;

    private String actor;

    private String reasonCode;
}
