package com.positivity.price.internal.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.positivity.price.internal.enums.LocationTag;
import com.positivity.price.internal.enums.ServiceTag;

public record RestrictionRuleResponse(
        UUID ruleId,
        UUID productId,
        LocationTag locationTag,
        ServiceTag serviceTag,
        boolean active,
        LocalDate effectiveFrom,
        LocalDate effectiveTo) {
}