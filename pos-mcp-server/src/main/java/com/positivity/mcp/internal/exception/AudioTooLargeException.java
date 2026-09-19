package com.positivity.mcp.internal.exception;

/**
 * The submitted clip is over the server's size or duration limit (#2074). Maps to 413 {@code
 * AUDIO_TOO_LARGE}. Also raised for {@link
 * org.springframework.web.multipart.MaxUploadSizeExceededException}, which the servlet container
 * throws before the request reaches the controller.
 */
public class AudioTooLargeException extends RuntimeException {
    public AudioTooLargeException(String message) {
        super(message);
    }

    public AudioTooLargeException(String message, Throwable cause) {
        super(message, cause);
    }
}
