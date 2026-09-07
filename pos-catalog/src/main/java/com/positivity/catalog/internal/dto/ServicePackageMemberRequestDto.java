package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "One operation's membership of a package.")
public class ServicePackageMemberRequestDto {

    @NotNull
    @Schema(description = "Catalog service this membership names", requiredMode = REQUIRED)
    private UUID serviceId;

    @Schema(
            description = "Presentation and work order within the package; defaults to the end",
            requiredMode = NOT_REQUIRED)
    private Integer sequence;

    @Schema(
            description = "How many of this operation the package includes; defaults to 1",
            example = "1",
            requiredMode = NOT_REQUIRED)
    private BigDecimal quantity;

    @Schema(
            description = "True = included by definition (what makes a fleet requirement a requirement);"
                    + " false = an upsell the package offers. Defaults to true.",
            requiredMode = NOT_REQUIRED)
    private Boolean required;
}
