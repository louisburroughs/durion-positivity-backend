package com.positivity.catalog.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(
        description = "Two active labor standards from different sources that would answer for the same"
                + " vehicle and time type, disagreeing by more than the requested threshold. Curation"
                + " material: the platform still resolves one of them by precedence, this says the choice"
                + " is contested.")
public class LaborStandardConflictDto {

    @Schema(description = "Service whose operation the two rows describe")
    private UUID serviceId;

    @Schema(description = "Durion operation code, when the service carries one", example = "TIRE-INSTALL-SET-4")
    private String operationCode;

    @Schema(description = "Time class both rows publish", example = "MANUFACTURER_INSTALL")
    private String timeType;

    @Schema(description = "The narrower row's vehicle key, rendered for reading", example = "2019|Honda|Civic|*|*")
    private String vehicleKey;

    @Schema(description = "Source of the first row", example = "MICHELIN")
    private String sourceCode;

    @Schema(description = "Hours the first row publishes", example = "1.1")
    private BigDecimal laborHours;

    @Schema(description = "Identifier of the first row")
    private UUID standardId;

    @Schema(description = "Source of the second row", example = "MOCKGUIDE")
    private String otherSourceCode;

    @Schema(description = "Hours the second row publishes", example = "1.4")
    private BigDecimal otherLaborHours;

    @Schema(description = "Identifier of the second row")
    private UUID otherStandardId;

    @Schema(description = "Absolute difference between the two published times", example = "0.3")
    private BigDecimal differenceHours;
}
