package com.positivity.image.internal.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import java.util.List;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "image")
@Schema(description = "Represents an image stored in the POS system.")
public class ImageEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "Unique identifier of the image", example = "1")
    private Long id;

    @Schema(description = "Filename of the image", example = "logo.png")
    private String filename;

    @Schema(description = "Filesystem or URL path to the image file", example = "/images/logo.png")
    private String url;

    @ElementCollection
    @Schema(description = "Context tags or labels associated with the image")
    private List<String> context;

    @Enumerated(EnumType.STRING)
    @Schema(description = "Classification of the image", implementation = Classification.class)
    private Classification classification;

    /**
     * SHA-256 of the stored bytes, when this record has bytes at all (CAP-324 #1257).
     *
     * <p>Null for every record created before image content could be stored: those are served by
     * resolving {@link #url} as a filesystem path, and they keep working that way. The two are read
     * in that order — content first, path second — so a record that gains bytes stops depending on a
     * file this service may no longer have.
     */
    @Schema(description = "SHA-256 of the stored image bytes; null when the image is served from its url path")
    private String contentHash;

    @Schema(description = "MIME type of the stored bytes", example = "image/jpeg")
    private String contentType;

    @Schema(description = "Size of the stored bytes")
    private Long byteSize;

    /**
     * Where the bytes were fetched from, kept as evidence rather than as a render source.
     *
     * <p>A vendor URL stops being how an image is served the moment the bytes are held here; it does
     * not stop being how the image is explained when somebody asks where a picture came from.
     */
    @Schema(description = "Origin the bytes were fetched from; provenance only, never a render source")
    private String sourceUri;

    @org.springframework.data.annotation.CreatedDate
    @Column(name = "created_at", updatable = false)
    private java.time.Instant createdAt;

    @org.springframework.data.annotation.LastModifiedDate
    @Column(name = "updated_at")
    private java.time.Instant updatedAt;

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Long getByteSize() {
        return byteSize;
    }

    public void setByteSize(Long byteSize) {
        this.byteSize = byteSize;
    }

    public String getSourceUri() {
        return sourceUri;
    }

    public void setSourceUri(String sourceUri) {
        this.sourceUri = sourceUri;
    }

    public java.time.Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(java.time.Instant createdAt) {
        this.createdAt = createdAt;
    }

    public java.time.Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(java.time.Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    // Getters and setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public List<String> getContext() {
        return context;
    }

    public void setContext(List<String> context) {
        this.context = context;
    }

    public Classification getClassification() {
        return classification;
    }

    public void setClassification(Classification classification) {
        this.classification = classification;
    }
}
