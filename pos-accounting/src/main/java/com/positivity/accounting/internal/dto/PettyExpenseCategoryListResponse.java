package com.positivity.accounting.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Every petty-expense category of the tenant, in code order (#2511). */
@Schema(description = "The tenant's petty-expense categories")
public record PettyExpenseCategoryListResponse(
        @Schema(description = "Every category, active and inactive, in code order")
        List<PettyExpenseCategoryResponse> categories) {}
