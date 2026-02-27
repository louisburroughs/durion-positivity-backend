package com.positivity.mcp.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.jspecify.annotations.NonNull;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "llm_api_config")
public class LlmApiConfig {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, unique = true, length = 120)
    private String apiId;

    @Column(nullable = false, length = 120)
    private String model;

    @Column(nullable = false)
    private String baseUrl;

    @Column(nullable = false)
    private String apiKey;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "llm_api_config_headers", joinColumns = @JoinColumn(name = "llm_api_config_id"))
    @Column(name = "header_value")
    private Map<String, String> headers = new HashMap<>();

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
        var now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(@NonNull UUID id) {
        this.id = id;
    }

    public String getApiId() {
        return apiId;
    }

    public void setApiId(@NonNull String apiId) {
        this.apiId = apiId;
    }

    public String getModel() {
        return model;
    }

    public void setModel(@NonNull String model) {
        this.model = model;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(@NonNull String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(@NonNull String apiKey) {
        this.apiKey = apiKey;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(@NonNull Map<String, String> headers) {
        this.headers = headers;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(@NonNull OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(@NonNull OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
