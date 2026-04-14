package com.positivity.accounting.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for creating a new Mapping Key.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MappingKeyCreateRequest {

    @NotNull(message = "Posting category ID is required")
    private UUID postingCategoryId;

    @NotBlank(message = "Key name is required")
    @Size(max = 100, message = "Key name must not exceed 100 characters")
    private String keyName;

    @Size(max = 500, message = "Description must not exceed 500 characters")
    private String description;

    @NotBlank(message = "Created by is required")
    @Size(max = 50, message = "Created by must not exceed 50 characters")
    private String createdBy;
}
