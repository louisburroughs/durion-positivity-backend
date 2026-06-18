package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Postal address for an employee")
public class EmployeeAddressDto {

    @Schema(description = "First address line", example = "123 Main St", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String line1;

    @Schema(description = "Second address line", example = "Suite 200", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String line2;

    @Schema(description = "City", example = "Springfield", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String city;

    @Schema(description = "State, province, or region", example = "IL", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String region;

    @Schema(description = "Postal or ZIP code", example = "62704", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String postalCode;

    @Schema(description = "Country", example = "US", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String country;
}
