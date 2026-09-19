package com.positivity.mcp.internal.exception;

/**
 * The speech-to-text provider could not service this request (#2074): not configured, a
 * connect/read timeout, or the provider answered 5xx/429/401/403. Maps to 503 {@code
 * TRANSCRIPTION_UNAVAILABLE} — never a bare 500, since none of these indicate a defect in this
 * service.
 */
public class TranscriptionUnavailableException extends RuntimeException {
    public TranscriptionUnavailableException(String message) {
        super(message);
    }

    public TranscriptionUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
