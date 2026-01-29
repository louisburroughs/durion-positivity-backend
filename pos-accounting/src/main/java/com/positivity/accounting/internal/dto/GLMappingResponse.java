package com.positivity.accounting.internal.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * DTO for GL Mapping response.
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - GLMapping Response</a>
 */
public class GLMappingResponse {

    private String glMappingId;
    private String postingCategoryId;
    private String postingCategoryName;
    private String mappingKeyId;
    private String mappingKeyName;
    private String glAccountId;
    private String accountCode;
    private String accountName;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private Map<String, String> dimensions;
    private Instant createdAt;
    private String createdBy;

    // Constructors
    public GLMappingResponse() {
    }

    // Getters and Setters
    public String getGlMappingId() {
        return glMappingId;
    }

    public void setGlMappingId(String glMappingId) {
        this.glMappingId = glMappingId;
    }

    public String getPostingCategoryId() {
        return postingCategoryId;
    }

    public void setPostingCategoryId(String postingCategoryId) {
        this.postingCategoryId = postingCategoryId;
    }

    public String getPostingCategoryName() {
        return postingCategoryName;
    }

    public void setPostingCategoryName(String postingCategoryName) {
        this.postingCategoryName = postingCategoryName;
    }

    public String getMappingKeyId() {
        return mappingKeyId;
    }

    public void setMappingKeyId(String mappingKeyId) {
        this.mappingKeyId = mappingKeyId;
    }

    public String getMappingKeyName() {
        return mappingKeyName;
    }

    public void setMappingKeyName(String mappingKeyName) {
        this.mappingKeyName = mappingKeyName;
    }

    public String getGlAccountId() {
        return glAccountId;
    }

    public void setGlAccountId(String glAccountId) {
        this.glAccountId = glAccountId;
    }

    public String getAccountCode() {
        return accountCode;
    }

    public void setAccountCode(String accountCode) {
        this.accountCode = accountCode;
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }
}
