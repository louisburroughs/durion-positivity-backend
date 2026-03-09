package com.positivity.price.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record RestrictionEvaluationRequest(
    @NotEmpty @Valid List<RestrictionEvaluationItem> items
) {}