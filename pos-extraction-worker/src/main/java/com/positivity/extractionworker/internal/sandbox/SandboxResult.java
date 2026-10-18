package com.positivity.extractionworker.internal.sandbox;

import com.positivity.extractionworker.internal.enums.ReasonCode;
import org.jspecify.annotations.Nullable;

/**
 * What the child reports: written to {@code <callDir>/result.json}. The child processed hostile input,
 * so the parent treats this as untrusted and validates it before using it.
 *
 * @param outcome how the operation ended
 * @param reasonCode the rejection reason for {@code REJECTED}
 * @param pageCount pages counted by an inspection; for {@code RENDERED} the page index rendered
 */
public record SandboxResult(Outcome outcome, @Nullable ReasonCode reasonCode, int pageCount) {

    /** File the child writes a rendered page to, inside the call directory. */
    public static final String RENDERED_FILE = "page.png";

    public enum Outcome {
        ACCEPTED,
        REJECTED,
        RENDERED,
        NOT_RENDERABLE
    }

    public static SandboxResult accepted(int pageCount) {
        return new SandboxResult(Outcome.ACCEPTED, null, pageCount);
    }

    public static SandboxResult rejected(ReasonCode reason) {
        return new SandboxResult(Outcome.REJECTED, reason, 0);
    }

    public static SandboxResult rendered(int page) {
        return new SandboxResult(Outcome.RENDERED, null, page);
    }

    public static SandboxResult notRenderable() {
        return new SandboxResult(Outcome.NOT_RENDERABLE, null, 0);
    }
}
