package com.positivity.mcp.internal.scopegraph;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The lexicon file is structurally wrong: not the shape the loader reads, as opposed to a reference
 * that does not resolve, which the builder reports as a finding. The message names the entity key.
 */
public class EntityLexiconException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EntityLexiconException(@NonNull String message) {
        super(message);
    }

    public EntityLexiconException(@NonNull String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
