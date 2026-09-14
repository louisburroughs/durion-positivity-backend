package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload replacing the whole postal code set of a service area.
 *
 * <p>An envelope rather than a bare list, matching the {@code {"rules": [...]}} shape
 * {@code replaceCoverageRules} already uses for the same kind of operation, and leaving room for
 * further fields without breaking the contract.
 *
 * Issue: #1991
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Complete replacement postal code set for a service area")
public class ServiceAreaPostalCodesRequest {

    @Schema(
            description = "The postal codes the service area should cover after this call."
                    + " The set is replaced wholesale: any code absent here stops being covered.",
            requiredMode = REQUIRED)
    @NotEmpty(message = "service area must include at least one postal code")
    @Valid
    private List<ServiceAreaRequest.PostalCodeEntry> postalCodes;
}
