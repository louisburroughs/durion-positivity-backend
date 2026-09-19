package com.positivity.mcp.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /v1/mcp/conversations/{id}/messages/{messageId}/feedback} (#2075). A
 * repeat {@code POST} replaces the stored rating in full — an omitted {@code reason} or {@code
 * comment} clears the previously stored value, it does not leave it untouched.
 *
 * <p>{@code rating} and {@code reason} are typed as {@code String}, not an enum, on purpose: an
 * unrecognized value must reach bean validation and come back as 400 {@code VALIDATION_ERROR}
 * with a {@code fieldErrors} entry. An enum would instead fail Jackson deserialization
 * ({@code HttpMessageNotReadableException}) with no {@code fieldErrors}.
 */
@Schema(
        name = "MessageFeedbackRequest",
        description = "The caller's rating of one assistant answer. A repeat POST replaces it.")
public record MessageFeedbackRequest(
        @Schema(
                description = "helpful or not_helpful.",
                allowableValues = {"helpful", "not_helpful"},
                requiredMode = REQUIRED,
                example = "not_helpful")
        @NotBlank
        @Pattern(regexp = "helpful|not_helpful", message = "rating must be 'helpful' or 'not_helpful'")
        String rating,

        @Schema(
                description = "Optional reason. Omit to leave/clear it (a repeat POST without reason "
                        + "clears any previously stored reason).",
                allowableValues = {"incorrect", "incomplete", "not_relevant", "other"},
                requiredMode = NOT_REQUIRED,
                nullable = true)
        @Nullable
        @Pattern(
                regexp = "incorrect|incomplete|not_relevant|other",
                message = "reason must be one of 'incorrect', 'incomplete', 'not_relevant', 'other'")
        String reason,

        @Schema(
                maxLength = 1000,
                requiredMode = NOT_REQUIRED,
                nullable = true,
                description = "Optional free text, trimmed; blank is treated as absent. At most 1000 "
                        + "characters after trimming.")
        @Nullable
        @Size(max = 1000, message = "comment must be at most 1000 characters")
        String comment) {

    /** Trims {@code comment} before bean validation runs (Jackson calls the canonical constructor). Blank -> null. */
    public MessageFeedbackRequest {
        if (comment != null) {
            comment = comment.strip();
            if (comment.isEmpty()) {
                comment = null;
            }
        }
    }
}
