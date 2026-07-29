package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.customer.internal.domain.SegmentPredicate;
import com.positivity.customer.internal.enums.AudienceType;
import com.positivity.customer.internal.enums.SegmentType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to create or update an audience segment")
public class UpsertSegmentRequest {

    @Schema(description = "Unique segment name", example = "Fleet accounts on credit hold", requiredMode = REQUIRED)
    @NotBlank
    @Size(max = 150)
    private String name;

    @Schema(description = "What the segment is for", requiredMode = NOT_REQUIRED)
    @Nullable
    @Size(max = 1000)
    private String description;

    @Schema(description = "Which kind of party the segment targets", example = "COMMERCIAL", requiredMode = REQUIRED)
    @NotNull
    private AudienceType audienceType;

    @Schema(description = "STATIC for an explicit list, DYNAMIC for a stored predicate", requiredMode = REQUIRED)
    @NotNull
    private SegmentType type;

    @Schema(
            description = "Predicate tree; required for DYNAMIC segments and rejected for STATIC ones",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private SegmentPredicate predicate;

    @Schema(
            description = "Whether the segment can be bound to a campaign; defaults to true",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private Boolean active;
}
