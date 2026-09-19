package com.positivity.mcp.internal.exception;

/**
 * The clip decoded fine but produced nothing usable (#2074): a blank transcript after trimming, or
 * the provider rejected the clip as undecodable (its own 400). Maps to 422 {@code
 * UNINTELLIGIBLE_AUDIO}.
 */
public class UnintelligibleAudioException extends RuntimeException {
    public UnintelligibleAudioException(String message) {
        super(message);
    }

    public UnintelligibleAudioException(String message, Throwable cause) {
        super(message, cause);
    }
}
