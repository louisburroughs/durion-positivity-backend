package com.positivity.price.dto;

import com.positivity.price.enums.EvaluationContext;
import com.positivity.price.enums.LocationTag;
import com.positivity.price.enums.ServiceTag;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record RestrictionEvaluationItem(
    @NotNull UUID productId,
    @NotNull LocationTag locationTag,
    @NotNull ServiceTag serviceTag,
    @NotNull EvaluationContext context
) {}