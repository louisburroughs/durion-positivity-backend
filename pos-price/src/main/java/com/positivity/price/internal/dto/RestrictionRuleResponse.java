package com.positivity.price.internal.dto;

import com.positivity.price.internal.enums.LocationTag;
import com.positivity.price.internal.enums.ServiceTag;
import java.time.LocalDate;
import java.util.UUID;

public record RestrictionRuleResponse(
        UUID ruleId,
        UUID productId,
        LocationTag locationTag,
        ServiceTag serviceTag,
        boolean active,
        LocalDate effectiveFrom,
        LocalDate effectiveTo) {}
