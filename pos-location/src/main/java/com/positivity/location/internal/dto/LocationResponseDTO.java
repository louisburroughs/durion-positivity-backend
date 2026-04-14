package com.positivity.location.internal.dto;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LocationResponseDTO {
    private UUID id;
    private String name;
    private String code;
    private UUID geographicalLocationId;
    private String addressLine1;
    private String addressLine2;
    private String city;
    private String state;
    private String postalCode;
    private String country;
    private String mailingAddress;
    private boolean active;
    private Long responsiblePersonId;
    private LocationTypeDTO type;
}
