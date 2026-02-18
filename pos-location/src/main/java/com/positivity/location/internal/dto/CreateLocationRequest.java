package com.positivity.location.internal.dto;

import com.positivity.location.internal.entity.ParentType;
import org.jspecify.annotations.NonNull;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.UUID;

@Getter
@Setter
public class CreateLocationRequest {

    @NonNull
    private String name;

    @NonNull
    private UUID typeId;

    private Map<ParentType, UUID> parents;

    private UUID geographicalLocationId;
}
