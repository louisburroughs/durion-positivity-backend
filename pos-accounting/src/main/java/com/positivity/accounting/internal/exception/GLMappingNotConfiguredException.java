package com.positivity.accounting.internal.exception;

import org.jspecify.annotations.Nullable;

/**
 * Thrown when GL mapping resolution finds no configured posting
 * category, mapping key, or effective mapping for the requested
 * source/date. The caller's request is valid; the posting configuration is
 * incomplete for this case. Maps to HTTP 422 (GL_MAPPING_NOT_CONFIGURED)
 * per ADR-0017 §2 — actionable by completing the GL mapping setup, not a
 * malformed request.
 *
 * <p>A posting that knows which mapping it needed names it (#2601): the category and key become the error's {@code
 * referenceId} ({@code CATEGORY/KEY}) and {@code nextAction} says what to set up before trying again.
 */
public class GLMappingNotConfiguredException extends RuntimeException {

    private final @Nullable String postingCategory;
    private final @Nullable String mappingKey;
    private final @Nullable String nextAction;

    public GLMappingNotConfiguredException(String message) {
        this(message, null, null, null);
    }

    public GLMappingNotConfiguredException(
            String message,
            @Nullable String postingCategory,
            @Nullable String mappingKey,
            @Nullable String nextAction) {
        super(message);
        this.postingCategory = postingCategory;
        this.mappingKey = mappingKey;
        this.nextAction = nextAction;
    }

    public @Nullable String getPostingCategory() {
        return postingCategory;
    }

    public @Nullable String getMappingKey() {
        return mappingKey;
    }

    public @Nullable String getNextAction() {
        return nextAction;
    }

    /** {@code CATEGORY/KEY} of the missing mapping, or null when the thrower did not name it. */
    public @Nullable String getReferenceId() {
        return postingCategory == null || mappingKey == null ? null : postingCategory + "/" + mappingKey;
    }
}
