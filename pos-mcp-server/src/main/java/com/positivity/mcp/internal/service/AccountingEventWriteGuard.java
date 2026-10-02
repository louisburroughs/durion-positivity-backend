package com.positivity.mcp.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.positivity.mcp.internal.enums.NltiRiskLevel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * #2374: the write-safety guard on pos-accounting's event retry and reprocess, the two accounting
 * event writes the assistant keeps ({@code submitAccountingEvent} is excluded from discovery: the
 * assistant never acts as the upstream producer of a source-system fact).
 *
 * <p>Both re-run a posting. Once confirmed, a journal entry can reach the ledger that only a
 * reversing entry corrects, which is HIGH risk under ADR-0068 ("a posting to accounting"). The
 * confirmation flows hold the generic rules (preview first, exact persisted arguments, permission at
 * both ends); this guard adds what they cannot know about these two operations:
 *
 * <ul>
 *   <li><strong>Risk floor.</strong> A plan for either tool is HIGH whatever the classifier said.
 *   <li><strong>Status precondition.</strong> Before the preview, and again just before execution,
 *       the event is read as the caller ({@code getAccountingEvent}) and must be FAILED (retry) or
 *       SUSPENDED (reprocess). pos-accounting's retry does not check the status itself.
 *   <li><strong>No blind retry.</strong> RECEIVED or PROCESSING means an earlier attempt may still be
 *       running, so the guard refuses and points at the event and its reprocessing history. The read
 *       is the poll: an unknown or timed-out outcome is never retried without one.
 *   <li><strong>One event per confirmation.</strong> The arguments must name exactly one {@code
 *       eventId}. A plan carries one argument set, and the chat path executes at most one guarded
 *       call per turn ({@code OpenApiToolProvider}).
 *   <li><strong>Preview.</strong> Event id, type, source, amount (or a payload summary), the mapping
 *       version for a reprocess, that a journal entry will post, and that there is no undo.
 * </ul>
 *
 * <p>Fail-closed: an event that cannot be read (missing, not permitted, unreachable), a malformed
 * {@code eventId}, or an unreadable answer refuses. The read goes through the same {@link
 * WritePlanExecutor} a confirmed plan uses, so it carries the caller's token and lands in the
 * invocation log.
 */
@Component
public class AccountingEventWriteGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger(AccountingEventWriteGuard.class);

    /** Discovered tool names, as {@code OpenApiToolMapper.discoveredToolName} derives them. */
    static final String RETRY_TOOL = "accounting_retryaccountingevent";

    static final String REPROCESS_TOOL = "accounting_reprocesssuspendedevent";

    /** The read the precondition runs through: {@code GET /accounting/v1/accounting/events/{eventId}}. */
    static final String EVENT_READ_TOOL = "accounting_getaccountingevent";

    static final String HISTORY_READ_TOOL = "accounting_geteventreprocessinghistory";

    /** The reads a guarded write is offered with on the chat path: the status check and the poll. */
    public static final List<String> READ_TOOLS = List.of(EVENT_READ_TOOL, HISTORY_READ_TOOL);

    private static final String PATH_PARAMS = "pathParams";
    private static final String EVENT_ID = "eventId";
    private static final String STATUS = "status";
    private static final int PAYLOAD_SUMMARY_FIELDS = 5;

    private final WritePlanExecutor executor;
    private final ObjectMapper objectMapper;

    /** Reads amounts as written ({@code 150.00}, not {@code 150.0}): the preview shows money. */
    private final ObjectReader eventReader;

    public AccountingEventWriteGuard(@NonNull WritePlanExecutor executor, @NonNull ObjectMapper objectMapper) {
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.eventReader = objectMapper
                .reader()
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES);
    }

    /** The guarded operation a tool name stands for, or null when the tool is not guarded. */
    private static @Nullable GuardedOperation operationOf(@Nullable String toolName) {
        if (RETRY_TOOL.equals(toolName)) {
            return GuardedOperation.RETRY;
        }
        if (REPROCESS_TOOL.equals(toolName)) {
            return GuardedOperation.REPROCESS;
        }
        return null;
    }

    /**
     * True for the accounting event writes this guard covers. Static so a caller without the guard
     * wired can still recognise a guarded tool, and refuse it (fail-closed).
     */
    public static boolean guards(@Nullable String toolName) {
        return operationOf(toolName) != null;
    }

    /** The lowest risk a plan for {@code toolName} may carry: HIGH for a guarded tool, else {@code risk}. */
    public static @NonNull NltiRiskLevel riskFloor(@NonNull String toolName, @NonNull NltiRiskLevel risk) {
        return guards(toolName) ? NltiRiskLevel.HIGH : risk;
    }

    /**
     * Reads the event the arguments name and decides whether the guarded call may be previewed or
     * run. On success {@link Inspection#message()} is the preview; otherwise it is the refusal,
     * written for the user. Never throws for a guarded tool: every failure is a refusal.
     *
     * @param args the tool envelope the call executes with ({@code pathParams}, {@code body}, ...)
     * @param authHeader the caller's {@code Authorization} header, relayed on the read
     */
    public @NonNull Inspection inspect(
            @NonNull String toolName, @Nullable Map<String, Object> args, @Nullable String authHeader) {
        GuardedOperation operation = operationOf(toolName);
        if (operation == null) {
            throw new IllegalArgumentException("Not a guarded accounting event write: " + toolName);
        }
        String eventId = eventIdOf(args);
        if (eventId == null) {
            return Inspection.refused(
                    "A " + operation.verb + " needs exactly one accounting event id (a UUID) in pathParams.eventId; one"
                            + " confirmation covers one event. Nothing was run.",
                    null,
                    null);
        }
        JsonNode event;
        try {
            event = eventReader.readTree(executor.execute(
                    EVENT_READ_TOOL,
                    objectMapper.writeValueAsString(Map.of(PATH_PARAMS, Map.of(EVENT_ID, eventId))),
                    authHeader));
        } catch (JsonProcessingException | RuntimeException unreadable) {
            LOGGER.warn(
                    "Accounting event guard could not read event={} tool={} error={}",
                    eventId,
                    toolName,
                    unreadable.getClass().getSimpleName());
            return Inspection.refused(
                    "Could not read accounting event " + eventId + " to check its status, so nothing was run. Check"
                            + " the event id and that you may view accounting events, then try again.",
                    eventId,
                    null);
        }
        String status = event == null ? null : text(event, STATUS);
        if (event == null || status == null) {
            return Inspection.refused(
                    "Accounting event " + eventId + " came back without a status, so nothing was run.", eventId, null);
        }
        String normalized = status.toUpperCase(Locale.ROOT);
        if (!operation.requiredStatus.equals(normalized)) {
            return Inspection.refused(refusal(operation, eventId, normalized, event), eventId, normalized);
        }
        return Inspection.permitted(preview(operation, eventId, normalized, event, args), eventId, normalized);
    }

    /**
     * The guard's rules in the words the model reads, appended to the description of a guarded
     * chat-path tool. The server enforces the status check and the one-event limit regardless.
     */
    public static @NonNull String guardNote(@NonNull String toolName) {
        GuardedOperation operation = operationOf(toolName);
        if (operation == null) {
            return "";
        }
        return " Write guard: HIGH risk, a posting to accounting. Before you preview, read the event with "
                + EVENT_READ_TOOL + " and check its status is " + operation.requiredStatus + ". The preview names the"
                + " event id, type, source and amount"
                + (operation == GuardedOperation.REPROCESS
                        ? ", the mapping version (mappingVersionToUse, or the active version when omitted)"
                        : "")
                + ", says a journal entry will post, and says there is no undo: a posted entry is corrected with a"
                + " reversing entry. One confirmation covers one eventId: never batch events and never swap the"
                + " event after the user confirmed. If an earlier call's outcome is unknown or timed out, read the"
                + " event and " + HISTORY_READ_TOOL + " first and never call again blindly. The server re-reads the"
                + " status and runs at most one of these calls per turn.";
    }

    private static @NonNull String refusal(
            @NonNull GuardedOperation operation,
            @NonNull String eventId,
            @NonNull String status,
            @NonNull JsonNode event) {
        String subject = "Accounting event " + eventId + " is " + status;
        return switch (status) {
            case "RECEIVED", "PROCESSING" ->
                subject + ": an earlier attempt may still be running, so nothing was run. Do not " + operation.verb
                        + " it again; read the event and its reprocessing history until it settles.";
            case "PROCESSED" ->
                subject + optionalSuffix(" (journal entry ", text(event, "journalEntryId"), ")")
                        + ": it has already posted, so nothing was run. A posted entry is corrected with a"
                        + " reversing entry, never by posting the event again.";
            default ->
                subject + ": only a " + operation.requiredStatus + " event can be " + operation.pastTense
                        + ", so nothing was run." + operation.alternativeFor(status);
        };
    }

    private @NonNull String preview(
            @NonNull GuardedOperation operation,
            @NonNull String eventId,
            @NonNull String status,
            @NonNull JsonNode event,
            @Nullable Map<String, Object> args) {
        StringBuilder preview = new StringBuilder("Accounting event ")
                .append(eventId)
                .append(" (")
                .append(orUnknown(text(event, "eventType")))
                .append(" from ")
                .append(orUnknown(text(event, "sourceSystem")))
                .append(", ")
                .append(amountOrPayloadSummary(event.get("payload")))
                .append(") is ")
                .append(status);
        String failure = text(event, "failureReasonCode");
        if (failure != null) {
            preview.append(" (").append(failure).append(')');
        }
        preview.append(". ").append(operation.preview);
        if (operation == GuardedOperation.REPROCESS) {
            String mappingVersion = mappingVersionOf(args);
            preview.append(" with ")
                    .append(
                            mappingVersion == null
                                    ? "the active mapping version"
                                    : "mapping version " + mappingVersion);
        }
        return preview.append(", and a journal entry will post. There is no undo: a posted entry is corrected with a")
                .append(" reversing entry. This confirmation covers this one event only.")
                .toString();
    }

    /** The first amount-like payload field ({@code totalAmount=150.00}), else the payload's field names. */
    private static @NonNull String amountOrPayloadSummary(@Nullable JsonNode payload) {
        if (payload == null || !payload.isObject() || payload.isEmpty()) {
            return "no payload";
        }
        List<String> names = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> fields = payload.fields(); fields.hasNext(); ) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getKey().toLowerCase(Locale.ROOT).contains("amount")
                    && field.getValue().isValueNode()) {
                JsonNode amount = field.getValue();
                return field.getKey() + "="
                        + (amount.isBigDecimal() ? amount.decimalValue().toPlainString() : amount.asText());
            }
            if (names.size() < PAYLOAD_SUMMARY_FIELDS) {
                names.add(field.getKey());
            }
        }
        return "payload " + String.join(", ", names) + (payload.size() > names.size() ? ", ..." : "");
    }

    /** The single event id the call targets: {@code pathParams.eventId}, a UUID, or null. */
    private static @Nullable String eventIdOf(@Nullable Map<String, Object> args) {
        if (args == null || !(args.get(PATH_PARAMS) instanceof Map<?, ?> pathParams)) {
            return null;
        }
        if (!(pathParams.get(EVENT_ID) instanceof String candidate) || candidate.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(candidate.strip()).toString();
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    private static @Nullable String mappingVersionOf(@Nullable Map<String, Object> args) {
        if (args == null || !(args.get("body") instanceof Map<?, ?> body)) {
            return null;
        }
        Object version = body.get("mappingVersionToUse");
        return version instanceof String text && !text.isBlank() ? text.strip() : null;
    }

    private static @Nullable String text(@NonNull JsonNode node, @NonNull String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private static @NonNull String orUnknown(@Nullable String value) {
        return value == null ? "unknown" : value;
    }

    private static @NonNull String optionalSuffix(
            @NonNull String prefix, @Nullable String value, @NonNull String suffix) {
        return value == null ? "" : prefix + value + suffix;
    }

    /**
     * The guard's decision. {@code message} is the preview when {@code permitted}, else the refusal;
     * {@code eventId} is the event the call targets (null when the arguments name none), and {@code
     * eventStatus} the status read (null when the event could not be read).
     */
    public record Inspection(
            boolean permitted,
            @NonNull String message,
            @Nullable String eventId,
            @Nullable String eventStatus) {

        static @NonNull Inspection permitted(
                @NonNull String preview, @NonNull String eventId, @NonNull String eventStatus) {
            return new Inspection(true, preview, eventId, eventStatus);
        }

        static @NonNull Inspection refused(
                @NonNull String refusal, @Nullable String eventId, @Nullable String eventStatus) {
            return new Inspection(false, refusal, eventId, eventStatus);
        }
    }

    private enum GuardedOperation {
        RETRY(
                "retry",
                "retried",
                "FAILED",
                "Retrying it re-runs its posting with its original payload and the current" + " rules"),
        REPROCESS("reprocess", "reprocessed", "SUSPENDED", "Reprocessing it re-runs its posting");

        private final String verb;
        private final String pastTense;
        private final String requiredStatus;
        private final String preview;

        GuardedOperation(String verb, String pastTense, String requiredStatus, String preview) {
            this.verb = verb;
            this.pastTense = pastTense;
            this.requiredStatus = requiredStatus;
            this.preview = preview;
        }

        /** Points a wrong-status event at the operation that does apply, where one does. */
        private @NonNull String alternativeFor(@NonNull String status) {
            if (this == RETRY && "SUSPENDED".equals(status)) {
                return " A SUSPENDED event is reprocessed once its mapping is fixed (reprocessSuspendedEvent).";
            }
            if (this == REPROCESS && "FAILED".equals(status)) {
                return " A FAILED event is retried (retryAccountingEvent).";
            }
            return "";
        }
    }
}
