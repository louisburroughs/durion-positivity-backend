package com.positivity.tenant.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** {@code POST /v1/platform/reading-tiers} (#2619). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "A new reading tier: its code, display name and monthly allowance")
public class ReadingTierCreateRequest {

    @Schema(
            description = "Tier code, upper case, 2 to 32 characters, unique; never changes",
            example = "PRO",
            requiredMode = REQUIRED)
    @NotBlank
    @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$")
    private String code;

    @Schema(description = "Display name", example = "Pro", requiredMode = REQUIRED)
    @NotBlank
    @Size(max = 200)
    private String displayName;

    @Schema(description = "Monthly document-reading allowance", requiredMode = REQUIRED)
    @NotNull
    @Valid
    private MonthlyAllowanceDto monthlyAllowance;
}
