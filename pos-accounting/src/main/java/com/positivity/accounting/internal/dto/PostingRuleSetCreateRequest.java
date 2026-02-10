package com.positivity.accounting.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for creating a new Posting Rule Set.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostingRuleSetCreateRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 100, message = "Name must not exceed 100 characters")
    private String name;

    @NotBlank(message = "Event type is required")
    @Size(max = 100, message = "Event type must not exceed 100 characters")
    private String eventType;

    @Size(max = 500, message = "Description must not exceed 500 characters")
    private String description;

    @NotBlank(message = "Rules definition is required")
    private String rulesDefinition;

    @NotBlank(message = "Created by is required")
    @Size(max = 50, message = "Created by must not exceed 50 characters")
    private String createdBy;
}
