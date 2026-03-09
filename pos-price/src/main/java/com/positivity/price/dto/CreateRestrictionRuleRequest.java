package com.positivity.price.dto;

import com.positivity.price.enums.LocationTag;
import com.positivity.price.enums.ServiceTag;
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
    boolean overrideable
) {}