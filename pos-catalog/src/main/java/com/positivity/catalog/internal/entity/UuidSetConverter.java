package com.positivity.catalog.internal.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Stores a {@code Set<UUID>} in a {@code text} column as a comma-separated, lexically sorted list
 * of canonical UUID strings (ADR-0061 §2, #1878/#1885). Used for the materialised location-scope
 * ancestor sets on {@link ExtLocationReplica}.
 *
 * <p>Sorted on write so the persisted form of a given set is byte-identical regardless of the
 * order the walk produced it; an empty set is the empty string, never {@code NULL}, so the column
 * can stay {@code NOT NULL}. Reads return a mutable insertion-ordered set.
 */
@Converter
public class UuidSetConverter implements AttributeConverter<Set<UUID>, String> {

    private static final String SEPARATOR = ",";

    @Override
    public String convertToDatabaseColumn(Set<UUID> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return "";
        }
        return attribute.stream()
                .map(UUID::toString)
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.joining(SEPARATOR));
    }

    @Override
    public Set<UUID> convertToEntityAttribute(String dbData) {
        Set<UUID> ids = new LinkedHashSet<>();
        if (dbData == null || dbData.isBlank()) {
            return ids;
        }
        for (String token : dbData.split(SEPARATOR)) {
            String trimmed = token.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                ids.add(UUID.fromString(trimmed));
            } catch (IllegalArgumentException e) {
                // Malformed persisted data — a server-side defect (bad prior write or manual DB
                // edit), never client input; surfaces as the platform's generic 500.
                throw new IllegalArgumentException("Malformed UUID in persisted ancestor set: " + trimmed, e);
            }
        }
        return ids;
    }
}
