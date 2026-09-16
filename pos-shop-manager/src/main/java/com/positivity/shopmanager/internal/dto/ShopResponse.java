package com.positivity.shopmanager.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Scheduling configuration for one location")
public class ShopResponse {

    /** The pos-location location id this shop configures; shop and location share it. */
    private UUID id;

    private String name;
    private String address;

    /** Absent when none is configured, in which case the schedule view falls back to UTC. */
    private String timezone;
}
