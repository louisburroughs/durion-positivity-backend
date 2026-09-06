package com.positivity.bulkingest;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collection;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

/**
 * Decides what a failed bulk-ingest row is allowed to tell its caller.
 *
 * <p>A bulk-ingest endpoint reports row outcomes inside a {@code 200} body, so no
 * {@code @ExceptionHandler} ever sees a row failure and the platform's ADR-0056 guarantee — a
 * server-side fault answers generically, and the correlation id logged with the stack trace is
 * the diagnostic handle — has to be honoured at the catch site instead. Before issue #1718 every
 * controller put {@code exception.getMessage()} straight into the row result, which echoed
 * Hibernate errors, {@code UUID.fromString} failures on stored data and any other internal fault
 * back to the caller verbatim, internal class names and query text included.
 *
 * <p>Two quite different things can fail a row, and they must not be reported the same way:
 *
 * <ul>
 *   <li><b>The row was rejected.</b> The exception is one the owning module's advice maps to a
 *       4xx, so its message describes the submitted record and is exactly what the caller needs
 *       in order to correct the row and resubmit. Reported with the module's own reason code and
 *       the message as written.
 *   <li><b>The server failed.</b> Anything else. Its message may name internal classes, columns
 *       or query text, so the caller gets {@link #INTERNAL_ERROR_CODE} and a correlation id and
 *       nothing else; the exception itself belongs in an ERROR log against that id.
 * </ul>
 *
 * <p>The split is deliberately an allowlist keyed on the module's own exception types: a type
 * nobody classified is reported generically, so the failure mode of an incomplete list is a
 * caller who has to quote a correlation id, never a leak.
 */
public final class BulkIngestFailures {

    /**
     * Reason code for a row that failed for a server-side reason.
     *
     * <p>Deliberately the same {@code INTERNAL_ERROR} that ADR-0056 fixes for a 500 body, not a
     * bulk-specific spelling of it: the code means one thing — the server failed, quote the
     * correlation id — and an operator or SDK matching on it should not have to know whether the
     * failure arrived as an {@code ApiError} envelope or as a row inside a 200. The two shapes are
     * already distinguishable without a second code.
     *
     * <p>Not documented in {@code docs/ERROR_ENVELOPE.md}: issue #1724 was closed not-planned with
     * the decision that the file covers the envelope shape and the {@code pos-web-common} fallback
     * codes only, and that each endpoint's own advice and OpenAPI spec are the source of truth for
     * the rest. A row result is not an {@code ApiError} and this is not a fallback code, so the
     * place it is documented is each bulk-ingest endpoint's {@code @Operation} description, which
     * names both codes it can return.
     *
     * <p>Shared across every domain for the same reason: the code, not the message, is what tells
     * a caller whether the row is theirs to fix or ours, and that distinction does not vary by
     * domain.
     */
    public static final String INTERNAL_ERROR_CODE = "INTERNAL_ERROR";

    /** Fixed text: everything a caller may act on is the code and the correlation id beside it. */
    private static final String INTERNAL_ERROR_MESSAGE = "Record could not be ingested because of a server-side error";

    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    /**
     * Request attribute under which the id resolved for this request is cached.
     *
     * <p>Without it a batch that arrives with no inbound {@code X-Correlation-Id} would mint a
     * fresh id per failing row, so a file with five hundred bad rows would answer with five hundred
     * unrelated ids and no way to group them — the opposite of the handle ADR-0056 relies on. One
     * id per request is what this promises and now what it does.
     */
    private static final String CORRELATION_ID_ATTRIBUTE = BulkIngestFailures.class.getName() + ".correlationId";

    private BulkIngestFailures() {
        // Utility class
    }

    /**
     * Whether {@code failure} is a rejection of the submitted record rather than a server-side
     * fault.
     *
     * <p>True when the exception is one of {@code rejectionTypes} — the module's own domain
     * exceptions that its advice answers as a 4xx — or when it classifies itself as a client
     * error by carrying a 4xx status, either as a {@link ResponseStatusException} or through a
     * {@link ResponseStatus} annotation on its class. The self-declaring cases are recognised
     * platform-wide so a module whose services throw them needs no list of its own.
     *
     * <p>The cause chain is not walked. A rejection wrapped in something else was re-thrown by
     * code that did not classify it, and it is the wrapper's message — the one that would be
     * echoed — that nobody has vouched for.
     */
    public static boolean isRowRejection(
            @NonNull Throwable failure, @NonNull Collection<Class<? extends Throwable>> rejectionTypes) {
        if (rejectionTypes.stream().anyMatch(type -> type.isInstance(failure))) {
            return true;
        }
        HttpStatusCode declared = declaredStatus(failure);
        return declared != null && declared.is4xxClientError();
    }

    /**
     * A row the owning service refused. The message is passed through because it is about the
     * record the caller sent; {@code fallbackMessage} covers an exception carrying none.
     */
    public static BulkIngestResult rejected(
            int rowIndex, @NonNull String errorCode, @NonNull Throwable failure, @NonNull String fallbackMessage) {
        String message = rejectionMessage(failure);
        return BulkIngestResult.builder()
                .rowIndex(rowIndex)
                .success(false)
                .errorCode(errorCode)
                .errorMessage(message == null || message.isBlank() ? fallbackMessage : message)
                .build();
    }

    /**
     * A {@link ResponseStatusException}'s {@code getMessage()} prefixes the status line
     * ({@code 400 BAD_REQUEST "firstName is required"}); the reason alone is what the caller
     * needs, and it is what the same exception would have produced through an advice.
     */
    @Nullable
    private static String rejectionMessage(Throwable failure) {
        return failure instanceof ResponseStatusException statusException
                ? statusException.getReason()
                : failure.getMessage();
    }

    /** The HTTP status the exception declares for itself, or null when it declares none. */
    @Nullable
    private static HttpStatusCode declaredStatus(Throwable failure) {
        if (failure instanceof ResponseStatusException statusException) {
            return statusException.getStatusCode();
        }
        ResponseStatus annotation =
                AnnotatedElementUtils.findMergedAnnotation(failure.getClass(), ResponseStatus.class);
        return annotation == null ? null : annotation.code();
    }

    /**
     * A row that failed for a server-side reason. Carries the correlation id and nothing more —
     * the caller quotes it, and the ERROR log entry written against the same id holds the detail.
     *
     * <p>The id is a field of its own rather than a sentence inside {@code errorMessage}: it is the
     * one machine-readable thing about this outcome, and a caller should not have to pattern-match
     * English to recover it.
     */
    public static BulkIngestResult internalError(int rowIndex, @NonNull String correlationId) {
        return BulkIngestResult.builder()
                .rowIndex(rowIndex)
                .success(false)
                .errorCode(INTERNAL_ERROR_CODE)
                .errorMessage(INTERNAL_ERROR_MESSAGE)
                .correlationId(correlationId)
                .build();
    }

    /**
     * The {@code X-Correlation-Id} of the request in flight, or a fresh UUID v7 when the caller
     * sent none — the same rule {@code GlobalApiExceptionHandler} applies, so an id quoted from a
     * row result and one quoted from an error envelope mean the same thing.
     *
     * <p>Resolved once per request and cached on it, so every failing row of one batch reports the
     * same id whether or not the caller supplied one. Off a request thread there is nothing to
     * cache on and each call mints its own.
     */
    public static String correlationId() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return UUIDv7Generator.generate().toString();
        }
        if (request.getAttribute(CORRELATION_ID_ATTRIBUTE) instanceof String cached) {
            return cached;
        }
        String inbound = request.getHeader(CORRELATION_ID_HEADER);
        String resolved = inbound == null || inbound.isBlank()
                ? UUIDv7Generator.generate().toString()
                : inbound.trim();
        request.setAttribute(CORRELATION_ID_ATTRIBUTE, resolved);
        return resolved;
    }

    @Nullable
    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest()
                : null;
    }
}
