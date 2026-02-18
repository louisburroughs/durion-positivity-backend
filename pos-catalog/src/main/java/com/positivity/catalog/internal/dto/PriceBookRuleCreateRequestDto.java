package com.positivity.catalog.internal.dto;

import com.positivity.catalog.internal.entity.PriceBookRuleConditionType;
import com.positivity.catalog.internal.entity.PriceBookRuleTargetType;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Data;

@Data
public class PriceBookRuleCreateRequestDto {

    @NotNull
    private PriceBookRuleTargetType targetType;

    private UUID targetId;

    @NotNull
    private String pricingLogic;

    private PriceBookRuleConditionType conditionType;

    private String conditionValue;

    private Integer priority;

    @NotNull
    private OffsetDateTime effectiveStartAt;

    private OffsetDateTime effectiveEndAt;

    @NotNull
    private UUID createdByUserId;

    private Long version;
}
