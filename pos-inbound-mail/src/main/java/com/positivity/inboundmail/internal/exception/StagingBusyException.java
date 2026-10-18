package com.positivity.inboundmail.internal.exception;

/**
 * Every fetch slot is in use. A staged attachment is held whole in memory while it is written out, so the
 * edge serves only {@code pos.inbound-mail.max-concurrent-fetches} at once; the caller retries.
 */
public class StagingBusyException extends RuntimeException {

    public static final String CODE = "STAGING_BUSY";

    public StagingBusyException() {
        super("Too many staged attachments are being served; retry shortly");
    }
}
