package com.positivity.accounting.internal.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for Posting Category.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostingCategoryResponse {

    private UUID postingCategoryId;
    private String categoryName;
    private String description;
    private Boolean isActive;
    private Instant createdAt;
    private String createdBy;
    private Instant modifiedAt;
    private String modifiedBy;
}
