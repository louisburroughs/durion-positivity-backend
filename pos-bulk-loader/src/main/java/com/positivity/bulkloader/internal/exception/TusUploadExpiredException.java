package com.positivity.bulkloader.internal.exception;

import java.util.UUID;

public class TusUploadExpiredException extends RuntimeException {

    public TusUploadExpiredException(UUID uploadId) {
        super("TUS upload has expired: " + uploadId);
    }
}
