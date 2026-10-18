package com.positivity.inboundmail.internal.exception;

/**
 * The ref names nothing the caller's tenant can read: unknown, another tenant's, collected or purged.
 * All four answer the same 404 (control 6: the response never says which).
 */
public class StagedAttachmentNotFoundException extends RuntimeException {

    public static final String CODE = "STAGED_ATTACHMENT_NOT_FOUND";

    public StagedAttachmentNotFoundException() {
        super("Staged attachment not found");
    }
}
