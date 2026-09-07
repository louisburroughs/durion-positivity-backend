package com.positivity.catalog.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "A service package with its members in presentation order.")
public class ServicePackageResponseDto {

    @Schema(description = "Package identifier")
    private UUID id;

    @Schema(description = "Stable package identity", example = "TIRE-INSTALL-PKG-4")
    private String packageCode;

    @Schema(description = "Display name")
    private String name;

    @Schema(description = "What the package covers")
    private String description;

    @Schema(description = "PLATFORM or SHOP", example = "PLATFORM")
    private String ownerScope;

    @Schema(description = "Owning location when ownerScope is SHOP; null for PLATFORM")
    private UUID ownerLocationId;

    @Schema(description = "Fleet account this is the requirement set for; null for a general offering")
    private UUID fleetPartyId;

    @Schema(description = "Authored labor hours for the package as sold", example = "1.2")
    private BigDecimal packageLaborHours;

    @Schema(description = "Whether the package is currently offered")
    private boolean active;

    @Schema(description = "First day the package is offered")
    private LocalDate effectiveFrom;

    @Schema(description = "Last day the package is offered")
    private LocalDate effectiveTo;

    @Schema(description = "Member operations, in presentation order")
    private List<ServicePackageMemberResponseDto> members;

    @Schema(description = "When the package was created")
    private Instant createdAt;
}
