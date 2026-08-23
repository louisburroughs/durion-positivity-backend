package com.positivity.inventory.internal.exception;

/**
 * The service that owns a receiving session's source document could not be reached (issue #1480).
 *
 * <p>Distinct from {@link SourceDocumentNotFoundException} on purpose: a transport failure means
 * the document's existence is unknown, and answering {@code 404} for it — as the retired stub
 * client effectively did for every request — tells the caller the opposite of the truth. Answered
 * as {@code 503}, which a caller can retry.
 */
public class SourceDocumentServiceUnavailableException extends RuntimeException {

    public static final String ERROR_CODE = "SOURCE_DOCUMENT_SERVICE_UNAVAILABLE";

    public SourceDocumentServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
