package com.positivity.image.internal.security;

/**
 * Permissions this module enforces (CAP-324 #1257).
 *
 * <h2>Why only the write is gated</h2>
 *
 * Reading an image has never required a permission here, and this story does not change that: the
 * read endpoints predate it and are used by page renders. Writing is different in kind. An ungated
 * store endpoint accepts arbitrary bytes from anyone who can reach the service and keeps them, which
 * is a storage-exhaustion and content-hosting problem regardless of who is asking.
 */
public final class ImagePermissions {

    /** Storing image bytes. */
    public static final String IMAGE_STORE = "image:image:store";

    private ImagePermissions() {}
}
