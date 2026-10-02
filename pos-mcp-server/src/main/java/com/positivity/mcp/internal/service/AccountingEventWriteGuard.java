package com.positivity.mcp.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.positivity.mcp.internal.enums.NltiRiskLevel;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
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
 *   <li><strong>Preview.</strong> Event id, type, source, amount (or a payload summary), the rules it
 *       posts under, that a journal entry will post, and that there is no undo. A reprocess that
 *       names {@code mappingVersionToUse} posts under that version. Otherwise (a retry, or a reprocess
 *       without a version) the posting follows whatever rules are active when it runs, so the guard
 *       dry-runs them ({@code resolveTestMapping}, the production evaluator with no version, exactly
 *       what such a posting uses) and names the matched rule version, or the default GL mappings, and
 *       the lines it would post. A dry run that matches nothing refuses: the call would not post.
 *   <li><strong>Pinning.</strong> {@link Inspection#fingerprint()} hashes the preview, so a caller
 *       that persists it (the write plan) can tell at confirmation whether anything the preview showed
 *       has changed since, the rules included.
 *   <li><strong>The caller's credential only.</strong> Arguments that carry their own {@code
 *       Authorization} header are refused: the executor would keep that header, so the status check
 *       and the write could run as different principals.
 * </ul>
 *
 * <p>Fail-closed: an event that cannot be read (missing, not permitted, unreachable), a malformed
 * {@code eventId}, rules that cannot be resolved, or an unreadable answer refuses. The reads go
 * through the same {@link WritePlanExecutor} a confirmed plan uses, so they carry the caller's token
 * and land in the invocation log.
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

    /** The dry run naming the rules an unpinned posting uses: {@code POST .../mappings/resolve-test}. */
    static final String RESOLVE_TEST_TOOL = "accounting_resolvetestmapping";

    /**
     * The tools a guarded write is offered with on the chat path: the status check, the poll and the
     * rules dry run (it persists nothing).
     */
    public static final List<String> COMPANION_TOOLS = List.of(EVENT_READ_TOOL, HISTORY_READ_TOOL, RESOLVE_TEST_TOOL);

    /** The key a write plan stores {@link Inspection#fingerprint()} under in its captured versions. */
    public static final String PIN_KEY = "accounting-event-guard";

    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}");
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
        if (carriesOwnAuthorization(args)) {
            return Inspection.refused(
                    "A guarded accounting event write runs only as the signed-in caller, and this call carries its"
                            + " own Authorization header, so nothing was run.",
                    eventId,
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
        String mappingVersion = operation == GuardedOperation.REPROCESS ? mappingVersionOf(args) : null;
        String rules;
        if (mappingVersion != null) {
            rules = "with mapping version " + mappingVersion;
        } else {
            Resolution resolution = resolveRules(operation, eventId, event, authHeader);
            if (!resolution.resolved()) {
                return Inspection.refused(resolution.text(), eventId, normalized);
            }
            rules = resolution.text();
        }
        return Inspection.permitted(preview(operation, eventId, normalized, event, rules), eventId, normalized);
    }

    /**
     * Dry-runs the rules an unpinned posting of this event uses, as the caller. The production
     * evaluator without a mapping version selects the latest published rule version for the event
     * type, else the default GL mappings, at the moment it runs; naming that here lets the preview
     * say what would post, and lets a persisted fingerprint catch a rule change before execution.
     */
    private @NonNull Resolution resolveRules(
            @NonNull GuardedOperation operation,
            @NonNull String eventId,
            @NonNull JsonNode event,
            @Nullable String authHeader) {
        String eventType = text(event, "eventType");
        String transactionDate = isoDateOf(event.get("transactionDate"));
        if (eventType == null || transactionDate == null) {
            return unresolved(operation, eventId, "the event has no type or transaction date to resolve them with");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventType", eventType);
        body.put("transactionDate", transactionDate);
        JsonNode payload = event.get("payload");
        if (payload != null && payload.isObject()) {
            body.put("samplePayload", payload);
        }
        JsonNode answer;
        try {
            answer = eventReader.readTree(executor.execute(
                    RESOLVE_TEST_TOOL, objectMapper.writeValueAsString(Map.of("body", body)), authHeader));
        } catch (JsonProcessingException | RuntimeException unreadable) {
            LOGGER.warn(
                    "Accounting event guard could not dry-run the rules event={} error={}",
                    eventId,
                    unreadable.getClass().getSimpleName());
            return unresolved(operation, eventId, "the dry run could not be read");
        }
        if (answer == null || !answer.path("matched").asBoolean(false)) {
            String reason = answer == null ? null : text(answer, "noMatchReason");
            return Resolution.refused("No posting rule or default GL mapping matches accounting event " + eventId
                    + optionalSuffix(" (", reason, ")") + ", so a " + operation.verb
                    + " would not post and nothing was run. Fix the mapping first.");
        }
        JsonNode rule = answer.get("matchedRule");
        String matched = rule == null || rule.isNull()
                ? "the default GL mappings (no published posting rule set)"
                : "posting rule set " + orUnknown(text(rule, "ruleSetName")) + " version "
                        + orUnknown(text(rule, "versionNumber")) + " (" + orUnknown(text(rule, "ruleVersionId")) + ")";
        return Resolution.resolved(
                "under the rules that apply now, " + matched + ": " + linesOf(answer.get("resolvedLines"))
                        + ". No mapping version is pinned, so a rule change before it runs voids this preview");
    }

    private static @NonNull Resolution unresolved(
            @NonNull GuardedOperation operation, @NonNull String eventId, @NonNull String why) {
        return Resolution.refused("Could not tell which posting rules a " + operation.verb + " of accounting event "
                + eventId + " would use (" + why + "), so nothing was run."
                + (operation == GuardedOperation.REPROCESS
                        ? " Name the version with mappingVersionToUse to pin it."
                        : ""));
    }

    /** The resolved journal lines, {@code DR 1100 150.00, CR 4000 150.00}. */
    private static @NonNull String linesOf(@Nullable JsonNode lines) {
        if (lines == null || !lines.isArray() || lines.isEmpty()) {
            return "no lines";
        }
        List<String> rendered = new ArrayList<>();
        for (JsonNode line : lines) {
            String account = text(line, "accountCode");
            account = account != null ? account : orUnknown(text(line, "glAccountId"));
            BigDecimal debit = decimal(line.get("debitAmount"));
            BigDecimal credit = decimal(line.get("creditAmount"));
            if (debit.signum() > 0) {
                rendered.add("DR " + account + " " + debit.toPlainString());
            }
            if (credit.signum() > 0) {
                rendered.add("CR " + account + " " + credit.toPlainString());
            }
        }
        return rendered.isEmpty() ? "no lines" : String.join(", ", rendered);
    }

    private static @NonNull BigDecimal decimal(@Nullable JsonNode amount) {
        return amount != null && amount.isNumber() ? amount.decimalValue() : BigDecimal.ZERO;
    }

    /** The date part of an ISO date-time ({@code 2026-08-13T10:15:00}) or a {@code [y, m, d, ...]} array. */
    private static @Nullable String isoDateOf(@Nullable JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isArray() && value.size() >= 3) {
            return String.format(
                    Locale.ROOT,
                    "%04d-%02d-%02d",
                    value.get(0).asInt(),
                    value.get(1).asInt(),
                    value.get(2).asInt());
        }
        String text = value.asText();
        return ISO_DATE.matcher(text).find() ? text.substring(0, 10) : null;
    }

    /** True when the call's own {@code headers} carry an {@code Authorization} entry, in any case. */
    private static boolean carriesOwnAuthorization(@Nullable Map<String, Object> args) {
        if (args == null || !(args.get("headers") instanceof Map<?, ?> headers)) {
            return false;
        }
        return headers.keySet().stream()
                .anyMatch(name -> name instanceof String header && "authorization".equalsIgnoreCase(header.strip()));
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
                + " event id, type, source and amount, the rules it posts under ("
                + (operation == GuardedOperation.REPROCESS ? "mappingVersionToUse when given; otherwise " : "")
                + "dry-run " + RESOLVE_TEST_TOOL + " with the event's type, transaction date and payload, and name the"
                + " rule version and lines it returns), says a journal entry will post, and says there is no undo: a"
                + " posted entry is corrected with a reversing entry. Never send your own Authorization header. One confirmation covers one eventId: never batch events and never swap the"
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
            @NonNull String rules) {
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
        return preview.append(". ")
                .append(operation.preview)
                .append(' ')
                .append(rules)
                .append(". A journal entry will post, and there is no undo: a posted entry is corrected with a")
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
     * {@code eventId} is the event the call targets (null when the arguments name none), {@code
     * eventStatus} the status read (null when the event could not be read), and {@code fingerprint}
     * a SHA-256 of the preview (null on a refusal): equal fingerprints mean everything the preview
     * showed, the status, the amount and the rules included, still holds.
     */
    public record Inspection(
            boolean permitted,
            @NonNull String message,
            @Nullable String eventId,
            @Nullable String eventStatus,
            @Nullable String fingerprint) {

        static @NonNull Inspection permitted(
                @NonNull String preview, @NonNull String eventId, @NonNull String eventStatus) {
            return new Inspection(true, preview, eventId, eventStatus, sha256(preview));
        }

        static @NonNull Inspection refused(
                @NonNull String refusal, @Nullable String eventId, @Nullable String eventStatus) {
            return new Inspection(false, refusal, eventId, eventStatus, null);
        }

        private static @NonNull String sha256(@NonNull String text) {
            try {
                return HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException unavailable) {
                throw new IllegalStateException("SHA-256 unavailable", unavailable);
            }
        }
    }

    /** The rules an unpinned posting uses, as preview text, or why they could not be told. */
    private record Resolution(boolean resolved, @NonNull String text) {

        static @NonNull Resolution resolved(@NonNull String text) {
            return new Resolution(true, text);
        }

        static @NonNull Resolution refused(@NonNull String text) {
            return new Resolution(false, text);
        }
    }

    private enum GuardedOperation {
        RETRY("retry", "retried", "FAILED", "Retrying it re-runs its posting with its original payload"),
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
