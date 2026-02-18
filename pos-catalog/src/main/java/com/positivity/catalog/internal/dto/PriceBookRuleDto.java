package com.positivity.catalog.internal.dto;

import com.positivity.catalog.internal.entity.PriceBookRuleConditionType;
import com.positivity.catalog.internal.entity.PriceBookRuleStatus;
import com.positivity.catalog.internal.entity.PriceBookRuleTargetType;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Data;

@Data
public class PriceBookRuleDto {
    private UUID ruleId;
    private UUID priceBookId;
    private PriceBookRuleTargetType targetType;
    private UUID targetId;
    private String pricingLogic;
    private PriceBookRuleConditionType conditionType;
    private String conditionValue;
    private Integer priority;
    private OffsetDateTime effectiveStartAt;
    private OffsetDateTime effectiveEndAt;
    private PriceBookRuleStatus status;
    private UUID createdByUserId;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private Long version;
}
