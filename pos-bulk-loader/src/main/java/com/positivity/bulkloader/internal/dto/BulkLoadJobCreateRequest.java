package com.positivity.bulkloader.internal.dto;

import com.positivity.bulkloader.internal.enums.DomainType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Data;

@Data
public class BulkLoadJobCreateRequest {

    @NotBlank
    private String fileName;

    @NotNull
    private DomainType domainType;

    private UUID locationId;
}
