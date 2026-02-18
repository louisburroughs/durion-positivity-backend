package com.positivity.location.internal.dto;

import com.positivity.location.internal.entity.ParentType;
import lombok.Builder;
import lombok.Getter;

import java.util.Map;
import java.util.UUID;

@Getter
@Builder
public class LocationDto {
    private UUID id;
    private String name;
    private String typeName;
    private Map<ParentType, UUID> parents;
    private UUID geographicalLocationId;
}
