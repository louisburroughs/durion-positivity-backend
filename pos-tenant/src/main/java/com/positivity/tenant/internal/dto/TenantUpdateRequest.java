package com.positivity.tenant.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.positivity.tenant.internal.validation.SupportedLocale;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Partial update of a tenant's mutable descriptors; slug and account are immutable. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Request payload for updating a tenant's display name or cell")
public class TenantUpdateRequest {

    @Schema(
            description = "New human-readable name, unique across the registry case- and whitespace-insensitively;"
                    + " omitted or null leaves it unchanged",
            requiredMode = NOT_REQUIRED)
    // @Size(min = 1) counted whitespace, so "   " passed it and normalized to the empty string —
    // a tenant with no name, unmatchable by a three-character search. @Pattern ignores null, so
    // the field stays optional and null still means "leave it unchanged".
    //
    // "Leading whitespace, then a non-whitespace character, then anything": \s and \S are disjoint,
    // so the match is a single left-to-right pass. The equivalent `.*\S.*` overlapped itself and
    // re-partitioned an all-whitespace value quadratically in its length. Kept to plain regex
    // constructs — this pattern is published in openapi.yaml and compiled by the SDK clients.
    @Pattern(regexp = "\\s*\\S[\\s\\S]*", message = "must not be blank")
    @Size(max = 200)
    private String displayName;

    @Schema(description = "New cell or region; omitted or null leaves it unchanged", requiredMode = NOT_REQUIRED)
    @Size(max = 64)
    private String cell;

    @Schema(
            description = "New locale, a BCP 47 tag with a region from pos.tenant.supported-locales; omitted or null"
                    + " leaves it unchanged",
            example = "fr-CA",
            requiredMode = NOT_REQUIRED)
    @Size(max = 35)
    @SupportedLocale
    private String locale;

    @Schema(
            description = "Move the tenant to another reading tier (CAP:550 S45); omitted or null leaves it. Must"
                    + " name an active tier, else 422 READING_TIER_NOT_ASSIGNABLE; publishes"
                    + " tenant.reading-allowance.changed",
            example = "PRO",
            requiredMode = NOT_REQUIRED)
    @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$")
    private String readingTierCode;
}
