package com.positivity.location.internal.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class LocationRequestDTO {
    @NotBlank(message = "name is required")
    private String name;

    @NotBlank(message = "code is required")
    private String code;

    private UUID geographicalLocationId;
    private String addressLine1;
    private String addressLine2;
    private String city;
    private String state;
    private String postalCode;
    private String country;
    private String mailingAddress;
    private Boolean active;
    private Long responsiblePersonId;
    private String timezone;
    private List<OperatingHoursRequest> operatingHours;
    private List<HolidayClosureRequest> holidayClosures;
    private Integer checkInBufferMinutes;
    private Integer cleanupBufferMinutes;

    @NotNull(message = "type is required")
    private LocationTypeDTO type;

    private Map<String, Object> parents;
}
