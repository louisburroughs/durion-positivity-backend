package com.positivity.documents;

import lombok.Builder;
import lombok.Data;

/**
 * Response DTO for document rendering.
 * Contains the rendered document data and metadata.
 *
 * Issue: ADR-0020
 */
@Data
@Builder
public class RenderResponse {

    /** Rendered document content (binary for PDF, text for HTML/CSV/TEXT) */
    private byte[] content;

    /** Content type (e.g., application/pdf, text/html) */
    private String contentType;

    /** Size of the rendered document in bytes */
    private long sizeBytes;

    /** Suggested filename for download */
    private String filename;

    /** Template ID that was used for rendering */
    private String templateId;

    /** Document format that was rendered */
    private DocumentFormat format;
}
