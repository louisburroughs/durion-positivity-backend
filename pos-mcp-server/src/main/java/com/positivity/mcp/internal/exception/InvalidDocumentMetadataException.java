package com.positivity.mcp.internal.exception;

/**
 * The client-supplied document ingestion metadata could not be serialized back to JSON (e.g. it
 * contains a value Jackson cannot round-trip). Raised synchronously from
 * {@code DocumentIngestionController#ingestDocument}, before the ingestion job is persisted --
 * genuine client input, not a server-side defect.
 */
public class InvalidDocumentMetadataException extends RuntimeException {
    public InvalidDocumentMetadataException(String message, Throwable cause) {
        super(message, cause);
    }
}
