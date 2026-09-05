package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.EventPayloadReference;
import com.positivity.accounting.internal.dto.ResolvedDisplayReference;
import com.positivity.accounting.internal.enums.DisplayReferenceType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the display projection that accompanies an accounting event's raw payload
 * (issue #1778).
 *
 * <p>The raw payload is an immutable audit artifact and is never modified, reordered or redacted
 * here — this projector only reads it. It walks the payload for keys known to carry a
 * UUID-backed reference, resolves each one through {@link DisplayReferenceResolver} (accounting's
 * own records and its event-fed replicas — no cross-domain call, ADR-0044), and returns one entry
 * per recognized value. Anything accounting cannot name is still projected, with null display
 * values: a caller then knows the reference exists and renders nothing for it, rather than being
 * handed a UUID dressed up as a label.
 *
 * <p>Key recognition is by name, case-insensitively and in both camelCase and snake_case, because
 * event payloads are free-form maps submitted by many producers. A key whose value does not parse
 * as a UUID is skipped — this is a display concern, and guessing at a malformed identifier would
 * be worse than omitting it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventPayloadReferenceProjector {

    /**
     * Payload keys that carry a reference of each type. Compared after lowercasing and stripping
     * underscores, so {@code vendorBillId}, {@code vendor_bill_id} and {@code VENDORBILLID} all
     * match the same entry.
     */
    private static final Map<String, DisplayReferenceType> RECOGNIZED_KEYS = Map.ofEntries(
            Map.entry("invoiceid", DisplayReferenceType.INVOICE),
            Map.entry("originalinvoiceid", DisplayReferenceType.INVOICE),
            Map.entry("priorinvoiceid", DisplayReferenceType.INVOICE),
            Map.entry("customerid", DisplayReferenceType.CUSTOMER),
            Map.entry("partyid", DisplayReferenceType.CUSTOMER),
            Map.entry("billtopartyid", DisplayReferenceType.CUSTOMER),
            Map.entry("organizationid", DisplayReferenceType.ORGANIZATION),
            Map.entry("locationid", DisplayReferenceType.LOCATION),
            Map.entry("journalentryid", DisplayReferenceType.JOURNAL_ENTRY),
            Map.entry("vendorid", DisplayReferenceType.VENDOR),
            Map.entry("vendorbillid", DisplayReferenceType.VENDOR_BILL));

    /**
     * Depth and size guards. Payloads are producer-supplied JSON of no fixed shape; these bound
     * the walk so a pathological document cannot turn one detail read into an unbounded one. Both
     * limits sit far above any payload this platform produces — hitting either is logged.
     */
    private static final int MAX_DEPTH = 12;

    private static final int MAX_REFERENCES = 200;

    private final DisplayReferenceResolver displayReferenceResolver;

    /**
     * Project the recognized references in one event payload.
     *
     * @param payload the event's raw payload; not modified, and may be null or empty
     * @return one entry per recognized UUID-backed value, ordered by payload path; empty when the
     *         payload holds no recognized reference
     */
    @NonNull
    @Transactional(readOnly = true)
    public List<EventPayloadReference> project(@Nullable Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return List.of();
        }

        List<FoundReference> found = new ArrayList<>();
        walk(payload, "", 0, found);
        if (found.isEmpty()) {
            return List.of();
        }

        // One resolver call per reference type present — never one per payload value.
        Map<DisplayReferenceType, Set<UUID>> idsByType = new EnumMap<>(DisplayReferenceType.class);
        for (FoundReference reference : found) {
            idsByType
                    .computeIfAbsent(reference.type(), type -> new LinkedHashSet<>())
                    .add(reference.id());
        }
        Map<DisplayReferenceType, Map<UUID, ResolvedDisplayReference>> resolved =
                new EnumMap<>(DisplayReferenceType.class);
        idsByType.forEach((type, ids) -> resolved.put(type, displayReferenceResolver.resolve(type, ids)));

        List<EventPayloadReference> projection = new ArrayList<>(found.size());
        for (FoundReference reference : found) {
            ResolvedDisplayReference display = resolved.getOrDefault(reference.type(), Map.of())
                    .getOrDefault(reference.id(), ResolvedDisplayReference.EMPTY);
            projection.add(EventPayloadReference.builder()
                    .path(reference.path())
                    .referenceType(reference.type())
                    .id(reference.id())
                    .displayName(display.displayName())
                    .displayReference(display.displayReference())
                    .build());
        }
        projection.sort(Comparator.comparing(EventPayloadReference::getPath));
        return List.copyOf(projection);
    }

    /** Depth-first walk of the payload document, accumulating recognized references. */
    private void walk(Object node, String path, int depth, List<FoundReference> found) {
        if (found.size() >= MAX_REFERENCES) {
            return;
        }
        if (depth > MAX_DEPTH) {
            log.debug("Stopped payload reference walk at path {}: depth limit {} reached", path, MAX_DEPTH);
            return;
        }
        // Payloads are producer JSON: an explicit null is an ordinary value, and a pattern switch
        // would throw on a null selector rather than fall through to the default branch.
        if (node == null) {
            return;
        }

        switch (node) {
            case Map<?, ?> map -> {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        continue;
                    }
                    String childPath = path.isEmpty() ? key : path + "." + key;
                    DisplayReferenceType type = RECOGNIZED_KEYS.get(normalizeKey(key));
                    if (type != null) {
                        UUID id = asUuid(entry.getValue());
                        if (id != null) {
                            found.add(new FoundReference(childPath, type, id));
                            if (found.size() >= MAX_REFERENCES) {
                                log.debug("Stopped payload reference walk: reference limit {} reached", MAX_REFERENCES);
                                return;
                            }
                            // A scalar reference has nothing beneath it: done with this key.
                            continue;
                        }
                        // A recognized key whose value is not a UUID is left unprojected — the raw
                        // payload still carries it verbatim for diagnostics — but the walk must
                        // still descend into it. Producers legitimately wrap a reference in an
                        // object ({"customerId": {"id": "...", "invoiceId": "..."}}), and
                        // returning here would silently drop every reference nested beneath a
                        // recognized key.
                    }
                    walk(entry.getValue(), childPath, depth + 1, found);
                }
            }
            case List<?> list -> {
                for (int i = 0; i < list.size(); i++) {
                    walk(list.get(i), path + "[" + i + "]", depth + 1, found);
                }
            }
            default -> {
                // Scalars are only interesting under a recognized key, handled by the map branch.
            }
        }
    }

    /** Lowercase and strip separators so camelCase and snake_case keys compare equal. */
    private static String normalizeKey(String key) {
        return key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    /**
     * Payload values arrive as whatever the producer serialized — usually a string, occasionally
     * an already-typed {@link UUID}. Anything else, or a malformed string, is not a reference.
     */
    @Nullable
    private static UUID asUuid(@Nullable Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(text.trim());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    /** A recognized reference located in the payload, before display resolution. */
    private record FoundReference(String path, DisplayReferenceType type, UUID id) {}
}
