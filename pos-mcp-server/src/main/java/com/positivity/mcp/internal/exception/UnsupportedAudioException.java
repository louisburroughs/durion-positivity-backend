package com.positivity.mcp.internal.exception;

/**
 * The request was not a well-formed audio submission (#2074): not multipart, the {@code audio}
 * part missing or empty, or its content type outside {@code audio/webm}, {@code audio/ogg}, {@code
 * audio/mp4} (codec parameters such as {@code ;codecs=opus} are ignored when matching). Maps to
 * 415 {@code UNSUPPORTED_AUDIO}.
 */
public class UnsupportedAudioException extends RuntimeException {
    public UnsupportedAudioException(String message) {
        super(message);
    }

    public UnsupportedAudioException(String message, Throwable cause) {
        super(message, cause);
    }
}
