package com.positivity.price.internal.dto;

import com.positivity.price.internal.enums.EvaluationContext;
import com.positivity.price.internal.enums.LocationTag;
import com.positivity.price.internal.enums.ServiceTag;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record RestrictionEvaluationItem(
        @NotNull UUID productId,
        @NotNull LocationTag locationTag,
        @NotNull ServiceTag serviceTag,
        @NotNull EvaluationContext context) {}
