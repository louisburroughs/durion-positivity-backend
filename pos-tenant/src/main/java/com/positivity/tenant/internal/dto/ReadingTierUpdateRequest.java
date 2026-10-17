package com.positivity.tenant.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** {@code PATCH /v1/platform/reading-tiers/{code}} (#2619): every field optional, null leaves it unchanged. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Changes to a reading tier; omitted fields are left unchanged")
public class ReadingTierUpdateRequest {

    @Schema(description = "New display name", example = "Professional", requiredMode = NOT_REQUIRED)
    @Pattern(regexp = "\\s*\\S[\\s\\S]*", message = "must not be blank")
    @Size(max = 200)
    private String displayName;

    @Schema(
            description = "New monthly allowance; publishes tenant.reading-allowance.changed for every tenant on the tier",
            requiredMode = NOT_REQUIRED)
    @Valid
    private MonthlyAllowanceDto monthlyAllowance;

    @Schema(
            description = "Activate or deactivate; deactivating a tier a non-decommissioned tenant is on is 409"
                    + " READING_TIER_IN_USE",
            example = "false",
            requiredMode = NOT_REQUIRED)
    private Boolean active;
}
