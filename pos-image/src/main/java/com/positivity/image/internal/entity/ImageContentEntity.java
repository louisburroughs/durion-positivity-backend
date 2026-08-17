package com.positivity.image.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The bytes of an image, addressed by their own SHA-256 (CAP-324 #1257).
 *
 * <h2>Content-addressed on purpose</h2>
 *
 * A manufacturer republishes the same artwork unchanged on every catalogue refresh. A store keyed on
 * anything but the content would take a fresh copy each cycle and grow without bound, while nothing
 * downstream could tell that two ids referred to the same picture.
 *
 * <p>The consequence worth knowing: rows here are shared. Several {@code image} records — different
 * filenames, different contexts, different vendors — may point at one of these. Deleting content
 * because one of them went away would blank the others.
 */
@Entity
@Table(name = "image_content")
public class ImageContentEntity {

    /** Lower-case hex SHA-256 of {@link #content}. The identity of an image is its bytes. */
    @Id
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    @Column(name = "content", nullable = false)
    private byte[] content;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

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

    public long getByteSize() {
        return byteSize;
    }

    public void setByteSize(long byteSize) {
        this.byteSize = byteSize;
    }

    public byte[] getContent() {
        return content;
    }

    public void setContent(byte[] content) {
        this.content = content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
