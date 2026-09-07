package com.positivity.catalog.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "One operation's membership of a package.")
public class ServicePackageMemberResponseDto {

    @Schema(description = "Membership identifier")
    private UUID id;

    @Schema(description = "Catalog service this membership names")
    private UUID serviceId;

    @Schema(description = "Durion operation code, when the service carries one", example = "WHEEL-BALANCE-SET-4")
    private String operationCode;

    @Schema(description = "Service name")
    private String serviceName;

    @Schema(description = "Presentation and work order within the package")
    private int sequence;

    @Schema(description = "How many of this operation the package includes", example = "1.00")
    private BigDecimal quantity;

    @Schema(description = "True = included by definition; false = an upsell the package offers")
    private boolean required;
}
