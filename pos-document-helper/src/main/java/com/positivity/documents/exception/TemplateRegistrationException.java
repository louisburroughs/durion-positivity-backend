package com.positivity.documents.exception;

/**
 * Exception thrown when template registration fails.
 * This is a runtime exception that wraps template registration errors.
 *
 * Issue: ADR-0020
 */
public class TemplateRegistrationException extends RuntimeException {

    private final String templateId;

    /**
     * Create a new template registration exception.
     *
     * @param message error message
     * @param templateId template that failed to register
     */
    public TemplateRegistrationException(String message, String templateId) {
        super(message);
        this.templateId = templateId;
    }

    /**
     * Create a new template registration exception with cause.
     *
     * @param message error message
     * @param cause underlying exception
     * @param templateId template that failed to register
     */
    public TemplateRegistrationException(String message, Throwable cause, String templateId) {
        super(message, cause);
        this.templateId = templateId;
    }

    public String getTemplateId() {
        return templateId;
    }

    @Override
    public String toString() {
        return String.format("TemplateRegistrationException[templateId=%s, message=%s]", templateId, getMessage());
    }
}
