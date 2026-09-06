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
 * (issues #1778, #1797).
 *
 * <p>The raw payload is an immutable audit artifact and is never modified, reordered or redacted
 * here — this projector only reads it. It walks the payload for keys known to carry a reference,
 * resolves each one through {@link DisplayReferenceResolver} (accounting's own records and its
 * event-fed replicas — no cross-domain call, ADR-0044), and returns one entry per recognized
 * value. Anything accounting cannot name is still projected, with null display values: a caller
 * then knows the reference exists and renders nothing for it, rather than being handed an
 * identifier dressed up as a label.
 *
 * <p>Key recognition is by name, case-insensitively and in both camelCase and snake_case, because
 * event payloads are free-form maps submitted by many producers. What counts as a usable value
 * depends on how the reference type is keyed ({@link DisplayReferenceType#isCodeKeyed()}): a
 * UUID-keyed type needs a value in canonical UUID form, while a code-keyed type such as
 * {@link DisplayReferenceType#LOCATION} takes any non-blank string up to the width of the code
 * column ({@value #MAX_CODE_LENGTH} characters), because accounting's location dimension is a code
 * ({@code LOC_USA}), not a UUID. A recognized key whose value fits neither is skipped — this is a
 * display concern, and guessing at a malformed identifier would be worse than omitting it.
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

    /**
     * Longest string accepted as a code-keyed reference value. Matches the width of
     * {@code accounting_location_profile.location_code}: nothing longer can be a location code,
     * so nothing longer is worth a lookup.
     */
    private static final int MAX_CODE_LENGTH = 100;

    private final DisplayReferenceResolver displayReferenceResolver;

    /**
     * Project the recognized references in one event payload.
     *
     * @param payload the event's raw payload; not modified, and may be null or empty
     * @return one entry per recognized reference value, ordered by payload path; empty when the
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

        // One resolver call per reference type present — never one per payload value. UUID-keyed
        // and code-keyed types are batched separately because they resolve by different shapes.
        Map<DisplayReferenceType, Set<UUID>> idsByType = new EnumMap<>(DisplayReferenceType.class);
        Map<DisplayReferenceType, Set<String>> codesByType = new EnumMap<>(DisplayReferenceType.class);
        for (FoundReference reference : found) {
            if (reference.type().isCodeKeyed()) {
                codesByType
                        .computeIfAbsent(reference.type(), type -> new LinkedHashSet<>())
                        .add(reference.rawValue());
            } else {
                idsByType
                        .computeIfAbsent(reference.type(), type -> new LinkedHashSet<>())
                        .add(reference.id());
            }
        }
        Map<DisplayReferenceType, Map<UUID, ResolvedDisplayReference>> resolvedById =
                new EnumMap<>(DisplayReferenceType.class);
        idsByType.forEach((type, ids) -> resolvedById.put(type, displayReferenceResolver.resolve(type, ids)));
        Map<DisplayReferenceType, Map<String, ResolvedDisplayReference>> resolvedByCode =
                new EnumMap<>(DisplayReferenceType.class);
        codesByType.forEach(
                (type, codes) -> resolvedByCode.put(type, displayReferenceResolver.resolveCodes(type, codes)));

        List<EventPayloadReference> projection = new ArrayList<>(found.size());
        for (FoundReference reference : found) {
            ResolvedDisplayReference display = reference.type().isCodeKeyed()
                    ? resolvedByCode
                            .getOrDefault(reference.type(), Map.of())
                            .getOrDefault(reference.rawValue(), ResolvedDisplayReference.EMPTY)
                    : resolvedById
                            .getOrDefault(reference.type(), Map.of())
                            .getOrDefault(reference.id(), ResolvedDisplayReference.EMPTY);
            projection.add(EventPayloadReference.builder()
                    .path(reference.path())
                    .referenceType(reference.type())
                    .rawValue(reference.rawValue())
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
                        FoundReference reference = recognize(childPath, type, entry.getValue());
                        if (reference != null) {
                            found.add(reference);
                            if (found.size() >= MAX_REFERENCES) {
                                log.debug("Stopped payload reference walk: reference limit {} reached", MAX_REFERENCES);
                                return;
                            }
                            // A scalar reference has nothing beneath it: done with this key.
                            continue;
                        }
                        // A recognized key whose value is not usable is left unprojected — the raw
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

    /**
     * Decide whether the value under a recognized key is a usable reference of the key's type.
     *
     * <p>A UUID-keyed type requires a value in canonical UUID form. A code-keyed type takes any
     * non-blank string of plausible length, and still records the parsed UUID when the code happens
     * to be one, so {@code id} is populated whenever it genuinely can be.
     */
    @Nullable
    private static FoundReference recognize(String path, DisplayReferenceType type, @Nullable Object value) {
        String rawValue = asText(value);
        if (rawValue == null) {
            return null;
        }
        UUID id = asUuid(rawValue);
        if (type.isCodeKeyed()) {
            return rawValue.length() > MAX_CODE_LENGTH ? null : new FoundReference(path, type, rawValue, id);
        }
        return id == null ? null : new FoundReference(path, type, rawValue, id);
    }

    /** Lowercase and strip separators so camelCase and snake_case keys compare equal. */
    private static String normalizeKey(String key) {
        return key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    /**
     * Payload values arrive as whatever the producer serialized — usually a string, occasionally
     * an already-typed {@link UUID}. Anything else, or a blank string, is not a reference value.
     * Strings are trimmed: surrounding whitespace is producer noise, not part of an identifier.
     */
    @Nullable
    private static String asText(@Nullable Object value) {
        if (value instanceof UUID uuid) {
            return uuid.toString();
        }
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        return text.trim();
    }

    /** Length of a UUID in its canonical {@code 8-4-4-4-12} text form. */
    private static final int CANONICAL_UUID_LENGTH = 36;

    /**
     * Parse a value as a UUID only when it is written in canonical form. {@link UUID#fromString}
     * is lenient — it accepts any five hyphen-separated hex groups, so {@code AB-CD-EF-01-23}
     * would parse to {@code 000000ab-00cd-00ef-0001-000000000023}. That leniency would let a
     * hyphenated location code, or a malformed identifier under a UUID-keyed key, fabricate an
     * {@code id} that appears nowhere in the payload; the length check rules it out.
     */
    @Nullable
    private static UUID asUuid(String text) {
        if (text.length() != CANONICAL_UUID_LENGTH) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    /**
     * A recognized reference located in the payload, before display resolution. {@code id} is
     * null only for a code-keyed type whose value is not UUID-shaped.
     */
    private record FoundReference(
            String path,
            DisplayReferenceType type,
            String rawValue,
            @Nullable UUID id) {}
}
