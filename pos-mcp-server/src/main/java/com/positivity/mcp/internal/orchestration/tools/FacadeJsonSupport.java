package com.positivity.mcp.internal.orchestration.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.NonNull;

/**
 * In-process JSON reshaping shared by facade tools whose real downstream endpoints return more
 * than the tool's intent (#1519 Wave 2): the location roster fetched whole and filtered here, and
 * the invoice line-row search collapsed to distinct invoices. Non-array or unparseable payloads
 * (error envelopes, unexpected shapes) pass through unchanged so the model still sees the
 * downstream answer.
 */
final class FacadeJsonSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FacadeJsonSupport() {}

    /**
     * Case-insensitive contains-filter over a JSON array of location objects, matching the
     * {@code name} and {@code code} fields.
     */
    static @NonNull String filterLocationsByNameOrCode(@NonNull String json, @NonNull String query) {
        JsonNode root = readTree(json);
        if (root == null || !root.isArray()) {
            return json;
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        ArrayNode filtered = MAPPER.createArrayNode();
        for (JsonNode location : root) {
            if (containsIgnoreCase(location, "name", needle) || containsIgnoreCase(location, "code", needle)) {
                filtered.add(location);
            }
        }
        return write(filtered, json);
    }

    /**
     * Collapse the invoice line rows returned by {@code GET /v1/invoices/items/search} into one
     * entry per owning invoice: grouped by {@code invoiceId} (falling back to
     * {@code invoiceNumber}), keeping the invoice-identifying fields plus a {@code lineCount} of
     * matched lines. The result is wrapped in a truncation envelope because the backing endpoint
     * bounds its answer to the newest {@code lineCap} line rows: {@code truncated} is true when
     * the scan hit that bound (so older invoices may exist beyond it), and
     * {@code coveredFrom}/{@code coveredTo} carry the oldest and newest {@code invoiceCreatedAt}
     * actually scanned (null when the rows carry no timestamps).
     */
    static @NonNull String distinctInvoicesFromLineRows(@NonNull String json, int lineCap) {
        JsonNode root = readTree(json);
        if (root == null || !root.isArray()) {
            return json;
        }
        Map<String, ObjectNode> byInvoice = new LinkedHashMap<>();
        String coveredFrom = null;
        String coveredTo = null;
        for (JsonNode line : root) {
            String createdAt = text(line, "invoiceCreatedAt");
            if (createdAt != null) {
                if (coveredFrom == null || compareTimestamps(createdAt, coveredFrom) < 0) {
                    coveredFrom = createdAt;
                }
                if (coveredTo == null || compareTimestamps(createdAt, coveredTo) > 0) {
                    coveredTo = createdAt;
                }
            }
            String key = text(line, "invoiceId") != null ? text(line, "invoiceId") : text(line, "invoiceNumber");
            if (key == null) {
                continue;
            }
            ObjectNode invoice = byInvoice.computeIfAbsent(key, unused -> {
                ObjectNode node = MAPPER.createObjectNode();
                copyIfPresent(line, node, "invoiceId", "invoiceNumber", "invoiceStatus", "invoiceCreatedAt");
                node.put("lineCount", 0);
                return node;
            });
            invoice.put("lineCount", invoice.get("lineCount").asInt() + 1);
        }
        ArrayNode invoices = MAPPER.createArrayNode();
        byInvoice.values().forEach(invoices::add);
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("truncated", root.size() >= lineCap);
        envelope.put("coveredFrom", coveredFrom);
        envelope.put("coveredTo", coveredTo);
        envelope.set("invoices", invoices);
        // Fallback stays envelope-shaped: returning the raw line rows here would hand the model
        // an un-flagged, possibly truncated array — the exact silent-truncation failure this
        // envelope exists to prevent.
        return write(
                envelope,
                "{\"truncated\":" + (root.size() >= lineCap)
                        + ",\"coveredFrom\":null,\"coveredTo\":null,\"invoices\":[],"
                        + "\"error\":\"invoice line rows could not be re-serialized\"}");
    }

    /**
     * Order two {@code invoiceCreatedAt} values on the instant timeline when both parse as
     * offset timestamps; lexicographic order is only the fallback. Mixed offsets (e.g.
     * {@code +02:00} beside {@code Z}) or varying fractional precision would otherwise misorder
     * the covered-range endpoints.
     */
    private static int compareTimestamps(String left, String right) {
        try {
            return OffsetDateTime.parse(left)
                    .toInstant()
                    .compareTo(OffsetDateTime.parse(right).toInstant());
        } catch (DateTimeParseException exception) {
            return left.compareTo(right);
        }
    }

    /**
     * Project a workorder payload down to its identity and lifecycle status. Payloads that do not
     * parse as an object carrying a {@code status} field (error envelopes, unexpected shapes) pass
     * through unchanged so the model still sees the downstream answer.
     */
    static @NonNull String workorderStatusProjection(@NonNull String json) {
        JsonNode root = readTree(json);
        if (root == null || !root.isObject() || text(root, "status") == null) {
            return json;
        }
        ObjectNode projected = MAPPER.createObjectNode();
        copyIfPresent(root, projected, "workorderId", "id", "workorderNumber", "status");
        return write(projected, json);
    }

    private static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private static String write(JsonNode node, String fallback) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            return fallback;
        }
    }

    private static boolean containsIgnoreCase(JsonNode node, String field, String needle) {
        String value = text(node, field);
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static void copyIfPresent(JsonNode source, ObjectNode target, String... fields) {
        for (String field : fields) {
            JsonNode value = source.get(field);
            if (value != null && !value.isNull()) {
                target.set(field, value);
            }
        }
    }
}
