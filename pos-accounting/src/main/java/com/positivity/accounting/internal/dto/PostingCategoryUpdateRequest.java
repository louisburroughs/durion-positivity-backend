package com.positivity.accounting.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for updating an existing Posting Category.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostingCategoryUpdateRequest {

    @NotBlank(message = "Category name is required")
    @Size(max = 100, message = "Category name must not exceed 100 characters")
    private String categoryName;

    @Size(max = 500, message = "Description must not exceed 500 characters")
    private String description;

    @NotBlank(message = "Modified by is required")
    @Size(max = 50, message = "Modified by must not exceed 50 characters")
    private String modifiedBy;
}
