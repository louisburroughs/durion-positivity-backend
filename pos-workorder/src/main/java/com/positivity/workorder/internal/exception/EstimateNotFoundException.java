package com.positivity.workorder.internal.exception;

import java.util.UUID;
import lombok.Getter;
import org.jspecify.annotations.NonNull;

/**
 * No estimate exists for the requested id (issue #1477).
 *
 * <p>Answered as {@code 404} with the canonical {@code ApiError} envelope, so a caller can tell a
 * wrong id apart from the other conditions that used to share promotion's bodiless {@code 400}.
 */
@Getter
public class EstimateNotFoundException extends RuntimeException {

    public static final String ERROR_CODE = "ESTIMATE_NOT_FOUND";

    private final @NonNull UUID estimateId;

    public EstimateNotFoundException(@NonNull UUID estimateId) {
        super("Estimate not found: " + estimateId);
        this.estimateId = estimateId;
    }
}
