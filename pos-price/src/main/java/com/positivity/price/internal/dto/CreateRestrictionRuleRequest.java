package com.positivity.price.internal.dto;

import com.positivity.price.internal.enums.LocationTag;
import com.positivity.price.internal.enums.ServiceTag;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

public record CreateRestrictionRuleRequest(
        @NotNull UUID productId,
        @NotNull LocationTag locationTag,
        @NotNull ServiceTag serviceTag,
        @NotNull LocalDate effectiveFrom,
        LocalDate effectiveTo,
        Integer policyVersion,
        boolean overrideable) {}
