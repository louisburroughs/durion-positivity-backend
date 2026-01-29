package com.positivity.accounting.internal.dto;

import java.time.LocalDate;
import java.util.Map;

/**
 * DTO for creating a new GL Mapping.
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - GLMapping Request</a>
 */
public class GLMappingCreateRequest {

    private String postingCategoryId;
    private String mappingKeyId;
    private String glAccountId;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private Map<String, String> dimensions;

    // Constructors
    public GLMappingCreateRequest() {
    }

    // Getters and Setters
    public String getPostingCategoryId() {
        return postingCategoryId;
    }

    public void setPostingCategoryId(String postingCategoryId) {
        this.postingCategoryId = postingCategoryId;
    }

    public String getMappingKeyId() {
        return mappingKeyId;
    }

    public void setMappingKeyId(String mappingKeyId) {
        this.mappingKeyId = mappingKeyId;
    }

    public String getGlAccountId() {
        return glAccountId;
    }

    public void setGlAccountId(String glAccountId) {
        this.glAccountId = glAccountId;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(LocalDate effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(LocalDate effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    public Map<String, String> getDimensions() {
        return dimensions;
    }

    public void setDimensions(Map<String, String> dimensions) {
        this.dimensions = dimensions;
    }
}
