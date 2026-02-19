package com.positivity.location.internal.dto;

import com.positivity.location.internal.entity.ParentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.UUID;

@Getter
@Setter
public class CreateLocationRequest {

    @NotBlank
    private String name;

    @NotNull
    private UUID typeId;

    private Map<ParentType, UUID> parents;

    private UUID geographicalLocationId;
}
