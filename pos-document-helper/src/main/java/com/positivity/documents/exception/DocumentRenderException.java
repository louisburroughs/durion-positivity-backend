package com.positivity.documents.exception;

/**
 * Exception thrown when document rendering fails.
 * This is a runtime exception that wraps underlying rendering errors.
 *
 * Issue: ADR-0020
 */
public class DocumentRenderException extends RuntimeException {

    private final String templateId;
    private final String format;

    /**
     * Create a new document render exception.
     *
     * @param message error message
     * @param templateId template that failed to render
     * @param format requested document format
     */
    public DocumentRenderException(String message, String templateId, String format) {
        super(message);
        this.templateId = templateId;
        this.format = format;
    }

    /**
     * Create a new document render exception with cause.
     *
     * @param message error message
     * @param cause underlying exception
     * @param templateId template that failed to render
     * @param format requested document format
     */
    public DocumentRenderException(String message, Throwable cause, String templateId, String format) {
        super(message, cause);
        this.templateId = templateId;
        this.format = format;
    }

    public String getTemplateId() {
        return templateId;
    }

    public String getFormat() {
        return format;
    }

    @Override
    public String toString() {
        return String.format(
                "DocumentRenderException[templateId=%s, format=%s, message=%s]", templateId, format, getMessage());
    }
}
