package com.positivity.shopmanager.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The scheduling configuration a location needs before it can be scheduled at all.
 *
 * <p>Carries no id: the shop's id <em>is</em> the pos-location location id, supplied on the path,
 * which is the convention every other read in this module already relies on when it resolves a
 * request's {@code locationId} through {@code ShopRepository}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Scheduling configuration for one location")
public class ShopUpsertRequest {

    @NotBlank
    @Size(max = 255)
    @Schema(description = "Display name of the shop", example = "Charlotte Main Service Center")
    private String name;

    @Size(max = 255)
    @Schema(description = "Street address of the shop", example = "100 Trade Street")
    private String address;

    /**
     * Validated as a real zone rather than stored blind: {@code getScheduleView} computes the day
     * window in this zone, so a typo here silently shifts every appointment on the board.
     */
    @Size(max = 64)
    @Schema(description = "IANA timezone the shop's day window is computed in", example = "America/New_York")
    private String timezone;
}
