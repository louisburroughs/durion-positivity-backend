package com.positivity.mcp.internal.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.enums.NltiRiskLevel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * #2497: the write-safety guard on pos-security-service's {@code requestAuditExport} when it delivers
 * by {@code WEBHOOK}, a sibling of {@link AccountingEventWriteGuard}.
 *
 * <p>A {@code DOWNLOAD} export keeps the file in the shop for whoever may download it, and stays an
 * ordinary write: unguarded, at whatever risk the classifier gave it. A {@code WEBHOOK} export sends
 * the tenant's audit data, signed, to the endpoint the tenant registered, outside the shop. That is
 * HIGH risk, and this guard adds what the generic confirmation flows cannot know:
 *
 * <ul>
 *   <li><strong>Risk floor.</strong> A plan whose body asks for anything but {@code DOWNLOAD} is HIGH
 *       whatever the classifier said. A body the guard cannot read as {@code DOWNLOAD} (a missing or
 *       unknown mode, a JSON string with duplicate keys) is treated as a webhook export: fail-closed.
 *   <li><strong>Destination precondition.</strong> Before the preview, and again just before
 *       execution, the destination is read as the caller ({@code getAuditExportWebhook}). None
 *       registered, or a read that fails, refuses: no plan, nothing run.
 *   <li><strong>Preview.</strong> The destination URL, an explicit "audit data will leave the shop
 *       to &lt;url&gt;" line, who registered it and when, the format and the filters, and that a
 *       delivered file cannot be recalled.
 *   <li><strong>Pinning.</strong> {@link Inspection#fingerprint()} hashes the preview, so a write plan
 *       whose destination was re-registered (another URL, or the same URL registered again) between
 *       preview and confirmation is cancelled as stale. On the chat path, which keeps no plan, the
 *       call must name the previewed URL in {@value #DESTINATION_HEADER}, and a different current
 *       destination refuses.
 *   <li><strong>The caller's credential only.</strong> Arguments carrying their own {@code
 *       Authorization} header are refused, so the read and the export run as the same principal.
 * </ul>
 *
 * <p>pos-security-service enforces the rest server-side: a WEBHOOK request with no registered
 * destination is a 422 and creates no job, the job pins the registration it was requested against,
 * and the destination's addresses are re-checked before every delivery attempt.
 */
@Component
public class AuditExportWriteGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuditExportWriteGuard.class);

    /** The discovered tool name of {@code POST /security-service/v1/audit/exports}. */
    static final String EXPORT_TOOL = "security-service_requestauditexport";

    /** The read the precondition runs through: {@code GET /security-service/v1/audit/exports/webhook}. */
    static final String DESTINATION_READ_TOOL = "security-service_getauditexportwebhook";

    /** The tools the export is offered with on the chat path: the destination read. */
    public static final List<String> COMPANION_TOOLS = List.of(DESTINATION_READ_TOOL);

    /** The key a write plan stores {@link Inspection#fingerprint()} under in its captured versions. */
    public static final String PIN_KEY = "audit-export-guard";

    /** Chat path: the header a WEBHOOK call names the destination it previewed in. */
    public static final String DESTINATION_HEADER = "X-Audit-Export-Destination";

    static final String DOWNLOAD = "DOWNLOAD";
    static final String WEBHOOK = "WEBHOOK";

    private static final String BODY = "body";
    private static final String DELIVERY_MODE = "deliveryMode";

    /** Reads a body sent as a JSON string; a duplicate key is refused rather than resolved. */
    private static final ObjectMapper STRICT_READER =
            new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private final WritePlanExecutor executor;
    private final ObjectMapper objectMapper;

    public AuditExportWriteGuard(@NonNull WritePlanExecutor executor, @NonNull ObjectMapper objectMapper) {
        this.executor = executor;
        this.objectMapper = objectMapper;
    }

    /**
     * True for the audit export tool, whatever its arguments: on the chat path the tool is always
     * offered through the guard, which lets a {@code DOWNLOAD} call straight through. Static so a
     * caller without the guard wired can still recognise the tool, and withhold it (fail-closed).
     */
    public static boolean covers(@Nullable String toolName) {
        return EXPORT_TOOL.equals(toolName);
    }

    /** True when this call of the audit export tool is not a plain {@code DOWNLOAD}: it must be inspected. */
    public static boolean guards(@Nullable String toolName, @Nullable Map<String, Object> args) {
        return covers(toolName) && !DOWNLOAD.equals(deliveryModeOf(args));
    }

    /** HIGH for a guarded (webhook) export, else {@code risk} unchanged: DOWNLOAD stays as classified. */
    public static @NonNull NltiRiskLevel riskFloor(
            @NonNull String toolName, @Nullable Map<String, Object> args, @NonNull NltiRiskLevel risk) {
        return guards(toolName, args) ? NltiRiskLevel.HIGH : risk;
    }

    /**
     * Reads the destination as the caller and decides whether the webhook export may be previewed or
     * run. On success {@link Inspection#message()} is the preview; otherwise it is the refusal, written
     * for the user. Never throws for a guarded call: every failure is a refusal.
     *
     * @param args the tool envelope the call executes with ({@code body}, {@code headers}, ...)
     * @param authHeader the caller's {@code Authorization} header, relayed on the read
     * @param requireNamedDestination true on the chat path: the call must name the previewed URL in
     *     {@value #DESTINATION_HEADER}. When the header is present it must match either way.
     */
    public @NonNull Inspection inspect(
            @NonNull String toolName,
            @Nullable Map<String, Object> args,
            @Nullable String authHeader,
            boolean requireNamedDestination) {
        if (!guards(toolName, args)) {
            throw new IllegalArgumentException("Not a guarded audit export: " + toolName);
        }
        if (!WEBHOOK.equals(deliveryModeOf(args))) {
            return Inspection.refused(
                    "An audit export needs body.deliveryMode DOWNLOAD or WEBHOOK, written once; nothing was run.",
                    null);
        }
        if (carriesOwnAuthorization(args)) {
            return Inspection.refused(
                    "A WEBHOOK audit export runs only as the signed-in caller, and this call carries its own"
                            + " Authorization header, so nothing was run.",
                    null);
        }
        JsonNode destination;
        try {
            destination = objectMapper.readTree(executor.execute(DESTINATION_READ_TOOL, "{}", authHeader));
        } catch (JsonProcessingException | RuntimeException unreadable) {
            LOGGER.warn(
                    "Audit export guard could not read the webhook destination tool={} error={}",
                    toolName,
                    unreadable.getClass().getSimpleName());
            return Inspection.refused(unreadableDestination(unreadable), null);
        }
        String url = destination == null ? null : text(destination, "url");
        if (url == null) {
            return Inspection.refused(
                    "The shop's audit export webhook came back without a URL, so nothing was run.", null);
        }
        String named = namedDestination(args);
        if (named == null && requireNamedDestination) {
            return Inspection.refused(
                    "A WEBHOOK audit export sends audit data outside the shop, to " + url + ". Preview that"
                            + " destination to the user and ask them to confirm, then call again naming it in"
                            + " headers." + DESTINATION_HEADER + ". Nothing was run.",
                    url);
        }
        if (named != null && !named.equals(url)) {
            return Inspection.refused(
                    "The shop's audit export webhook is now " + url + ", not " + named + " as previewed, so"
                            + " nothing was run. Preview the new destination and ask the user to confirm again.",
                    url);
        }
        return Inspection.permitted(preview(url, destination, bodyOf(args)), url);
    }

    private static @NonNull String unreadableDestination(@NonNull Exception unreadable) {
        String message = unreadable.getMessage() == null ? "" : unreadable.getMessage();
        if (message.contains("404") || message.contains("AUDIT_EXPORT_WEBHOOK_NOT_REGISTERED")) {
            return "No audit export webhook is registered for this shop, so a WEBHOOK export has nowhere to go and"
                    + " nothing was run. An administrator registers one first (registerAuditExportWebhook), or"
                    + " export with deliveryMode DOWNLOAD instead.";
        }
        return "Could not read the shop's audit export webhook destination, so nothing was run. Check that you"
                + " may export audit data, then try again, or export with deliveryMode DOWNLOAD instead.";
    }

    private static @NonNull String preview(
            @NonNull String url, @NonNull JsonNode destination, @Nullable Map<?, ?> body) {
        String format = body == null || !(body.get("format") instanceof String f) || f.isBlank()
                ? "unspecified-format"
                : f.strip().toUpperCase(Locale.ROOT);
        StringBuilder preview = new StringBuilder("Audit export by WEBHOOK: a ")
                .append(format)
                .append(" file of ")
                .append(filtersOf(body))
                .append(" will be signed and sent to ")
                .append(url);
        String registeredBy = text(destination, "registeredBy");
        String registeredAt = text(destination, "registeredAt");
        if (registeredBy != null || registeredAt != null) {
            preview.append(" (registered");
            if (registeredBy != null) {
                preview.append(" by ").append(registeredBy);
            }
            if (registeredAt != null) {
                preview.append(" at ").append(registeredAt);
            }
            preview.append(')');
        }
        return preview.append(". Audit data will leave the shop to ")
                .append(url)
                .append(". Once delivered it cannot be recalled. This confirmation covers this one export only.")
                .toString();
    }

    /** The filters in words: {@code audit events of type X from A to B}, or every audit event. */
    private static @NonNull String filtersOf(@Nullable Map<?, ?> body) {
        if (body == null || !(body.get("filters") instanceof Map<?, ?> filters) || filters.isEmpty()) {
            return "every audit event (no filters)";
        }
        List<String> parts = new ArrayList<>();
        appendFilter(parts, filters, "eventType", "of type ");
        appendFilter(parts, filters, "actorId", "by actor ");
        appendFilter(parts, filters, "aggregateId", "on ");
        appendFilter(parts, filters, "fromDate", "from ");
        appendFilter(parts, filters, "toDate", "until ");
        return parts.isEmpty() ? "every audit event (no filters)" : "the audit events " + String.join(", ", parts);
    }

    private static void appendFilter(
            @NonNull List<String> parts, @NonNull Map<?, ?> filters, @NonNull String key, @NonNull String label) {
        Object value = filters.get(key);
        if (value != null && !String.valueOf(value).isBlank()) {
            parts.add(label + String.valueOf(value).strip());
        }
    }

    /**
     * The guard's rules in the words the model reads, appended to the export tool's description on the
     * chat path. The server enforces the destination read, the pin and the one-write limit regardless.
     */
    public static @NonNull String guardNote(@NonNull String toolName) {
        if (!covers(toolName)) {
            return "";
        }
        return " Write guard: deliveryMode DOWNLOAD is an ordinary export. deliveryMode WEBHOOK is HIGH risk: it"
                + " sends the shop's audit data outside the shop. Before you preview a WEBHOOK export, read the"
                + " destination with " + DESTINATION_READ_TOOL + "; if none is registered, do not offer WEBHOOK. The"
                + " preview names that URL (\"audit data will leave the shop to <url>\"), the format and the"
                + " filters, says a delivered file cannot be recalled, and asks the user to confirm. On the call,"
                + " send headers." + DESTINATION_HEADER + " set to the URL you previewed, and never your own"
                + " Authorization header. The server re-reads the destination, refuses when it changed, and runs"
                + " at most one guarded write per turn.";
    }

    /** The URL a call names in {@value #DESTINATION_HEADER}, in any header-name case, or null. */
    static @Nullable String namedDestination(@Nullable Map<String, Object> args) {
        if (args == null || !(args.get("headers") instanceof Map<?, ?> headers)) {
            return null;
        }
        for (Map.Entry<?, ?> header : headers.entrySet()) {
            if (header.getKey() instanceof String name
                    && DESTINATION_HEADER.equalsIgnoreCase(name.strip())
                    && header.getValue() instanceof String value
                    && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }

    /**
     * The delivery mode the call's body asks for, upper-cased, or null when it cannot be read. A body
     * sent as a JSON string is parsed, since the executor forwards it as written.
     */
    static @Nullable String deliveryModeOf(@Nullable Map<String, Object> args) {
        Map<?, ?> body = bodyOf(args);
        if (body == null || !(body.get(DELIVERY_MODE) instanceof String mode) || mode.isBlank()) {
            return null;
        }
        return mode.strip().toUpperCase(Locale.ROOT);
    }

    private static @Nullable Map<?, ?> bodyOf(@Nullable Map<String, Object> args) {
        if (args == null) {
            return null;
        }
        Object body = args.get(BODY);
        if (body instanceof Map<?, ?> map) {
            return map;
        }
        if (body instanceof String json && !json.isBlank()) {
            try {
                return STRICT_READER.readValue(json, Map.class);
            } catch (JsonProcessingException | RuntimeException unreadable) {
                return null;
            }
        }
        return null;
    }

    /** True when the call's own {@code headers} carry an {@code Authorization} entry, in any case. */
    private static boolean carriesOwnAuthorization(@Nullable Map<String, Object> args) {
        if (args == null || !(args.get("headers") instanceof Map<?, ?> headers)) {
            return false;
        }
        return headers.keySet().stream()
                .anyMatch(name -> name instanceof String header && "authorization".equalsIgnoreCase(header.strip()));
    }

    private static @Nullable String text(@NonNull JsonNode node, @NonNull String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank()
                ? null
                : value.asText().strip();
    }

    /**
     * The guard's decision. {@code message} is the preview when {@code permitted}, else the refusal;
     * {@code destination} is the URL read (null when none could be read), and {@code fingerprint} a
     * SHA-256 of the preview (null on a refusal): equal fingerprints mean the destination, its
     * registration, the format and the filters the preview showed all still hold.
     */
    public record Inspection(
            boolean permitted,
            @NonNull String message,
            @Nullable String destination,
            @Nullable String fingerprint) {

        static @NonNull Inspection permitted(@NonNull String preview, @NonNull String destination) {
            return new Inspection(true, preview, destination, sha256(preview));
        }

        static @NonNull Inspection refused(@NonNull String refusal, @Nullable String destination) {
            return new Inspection(false, refusal, destination, null);
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
}
