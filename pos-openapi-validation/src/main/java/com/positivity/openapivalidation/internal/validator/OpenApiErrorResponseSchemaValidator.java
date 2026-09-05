package com.positivity.openapivalidation.internal.validator;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Checks that every 4xx/5xx response in a generated spec carries the canonical {@code ApiError}
 * envelope (ADR-0017 §3), rather than a success DTO (issue #1720).
 *
 * <p>An {@code @ApiResponse} for an error status that omits {@code content}/{@code schema} does
 * not produce an empty schema — springdoc fills it in by inference, and gets it wrong in two ways:
 *
 * <ol>
 *   <li>from the endpoint's own success type, so the spec tells clients the error body is the 200
 *       DTO; and</li>
 *   <li>from a {@code @ControllerAdvice} handler's return type, which means the schema silently
 *       degrades when that advice is removed — as happened to {@code AuthController#login} during
 *       #1694, caught only because the spec was diffed against a pristine baseline.</li>
 * </ol>
 *
 * <p>This check reads the <em>generated spec</em> rather than the annotations, because that is
 * the artifact the Angular SDK is generated from and the only place the inference is visible. The
 * failure mode is invisible in code review, which is why it needs a guard rather than a sweep.
 */
public class OpenApiErrorResponseSchemaValidator {

    /** The canonical envelope from pos-shared-dtos; ADR-0017 §3 makes it the body of every non-2xx. */
    static final String ERROR_ENVELOPE_SCHEMA = "ApiError";

    /**
     * Findings for one operation, as {@code "<STATUS> response body is <Schema>, not ApiError"}.
     * A response with no {@code content} at all is not reported: a genuinely bodiless error is a
     * legitimate contract, and springdoc emits no schema for it, so there is nothing to mistype.
     */
    public @NonNull List<String> check(@NonNull Operation operation) {
        if (operation.getResponses() == null) {
            return List.of();
        }
        List<String> findings = new ArrayList<>();
        for (Map.Entry<String, ApiResponse> entry : operation.getResponses().entrySet()) {
            if (!isErrorStatus(entry.getKey())) {
                continue;
            }
            ApiResponse response = entry.getValue();
            if (response == null || response.getContent() == null) {
                continue;
            }
            for (Map.Entry<String, MediaType> mediaEntry : response.getContent().entrySet()) {
                String schemaName = schemaName(mediaEntry.getValue());
                if (schemaName != null && !ERROR_ENVELOPE_SCHEMA.equals(schemaName)) {
                    findings.add(entry.getKey() + " response body is " + schemaName + ", not " + ERROR_ENVELOPE_SCHEMA
                            + " (ADR-0017 §3; a schema-less @ApiResponse lets springdoc infer the wrong type)");
                }
            }
        }
        return List.copyOf(findings);
    }

    /** {@code default} is deliberately not treated as an error status — it covers 2xx too. */
    private static boolean isErrorStatus(@Nullable String status) {
        return status != null
                && status.length() == 3
                && (status.charAt(0) == '4' || status.charAt(0) == '5')
                && Character.isDigit(status.charAt(1))
                && Character.isDigit(status.charAt(2));
    }

    /**
     * The referenced component name, or {@code null} when the body is not a named component —
     * an inline object, a string, or a binary payload cannot be an accidental success DTO.
     */
    private static @Nullable String schemaName(@Nullable MediaType mediaType) {
        if (mediaType == null) {
            return null;
        }
        Schema<?> schema = mediaType.getSchema();
        if (schema == null) {
            return null;
        }
        String ref = schema.get$ref();
        if (ref == null && schema.getItems() != null) {
            ref = schema.getItems().get$ref();
        }
        if (ref == null) {
            return null;
        }
        int lastSlash = ref.lastIndexOf('/');
        return lastSlash >= 0 ? ref.substring(lastSlash + 1) : ref;
    }
}
