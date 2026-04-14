package com.positivity.documents.helper.exception;

/**
 * Exception thrown when document rendering fails.
 *
 * Issue: ADR-0020
 */
public class DocumentRenderException extends DocumentHelperException {

    private final String templateId;

    public DocumentRenderException(String templateId, String message) {
        super(String.format("Document rendering failed for template '%s': %s", templateId, message));
        this.templateId = templateId;
    }

    public DocumentRenderException(String templateId, String message, Throwable cause) {
        super(String.format("Document rendering failed for template '%s': %s", templateId, message), cause);
        this.templateId = templateId;
    }

    public String getTemplateId() {
        return templateId;
    }
}
