package com.positivity.location.internal.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The one reader of the {@code location.operating_hours} / {@code location.holiday_closures} JSON
 * columns, shared by every consumer of them (issue #2139).
 *
 * <p>Both columns are free-form JSON written by {@code LocationServiceImpl}, and both the event
 * publisher ({@link LocationFactPublisher}) and the location read path need them back as typed
 * values. Each had its own parser until they drifted: the publisher's hardened all-or-nothing
 * reader (issue #2023) against a read-path reader that could still NPE past its own catch and
 * answer a partial entry. Two parsers of one column is one parser too many — this class is the
 * single implementation, and each caller maps {@link ParsedHours} / {@link ParsedClosure} into its
 * own shape.
 *
 * <p><strong>All-or-nothing.</strong> Every method answers {@code null} — meaning <em>not
 * readable / not configured</em> — for the whole column as soon as any part of it cannot be read:
 * malformed JSON, the literal JSON {@code null}, a {@code null} element, an unparseable day name,
 * time or date, or two entries colliding on the same day once canonicalized. A <em>partial</em>
 * list is deliberately never returned: dropping the one bad day and answering the rest would let a
 * consumer report that real, configured day as {@code CLOSED} — a worse and more misleading
 * failure than reporting the whole column as unknown.
 *
 * <p><strong>{@code null} is not {@code []}.</strong> A {@code null} column is <em>never
 * published</em> and answers {@code null}; {@code "[]"} is the distinct, legitimate fact
 * <em>published with no entries</em> (closed every day / no closures) and answers an empty list.
 *
 * <p><strong>The two columns are independent.</strong> Each method reads exactly one column, so a
 * malformed {@code operating_hours} can never null {@code holiday_closures} or the other way
 * round: they describe unrelated facts.
 *
 * <p>Callers pass their own {@link Logger} so the diagnostic lands under the class whose operation
 * degraded — the publish, or the read — rather than under this helper.
 */
final class LocationScheduleJson {

    /**
     * Deserializes the canonical JSON {@code LocationServiceImpl.serializeOperatingHours} /
     * {@code serializeHolidayClosures} write, mirroring the same library those methods use.
     */
    private static final ObjectMapper JSON_MAPPER = JsonMapper.builder().build();

    private LocationScheduleJson() {}

    /** One stored operating-hours entry, canonicalized; neutral of any caller's response or event shape. */
    record ParsedHours(
            @NonNull DayOfWeek day,
            @Nullable LocalTime openTime,
            @Nullable LocalTime closeTime) {}

    /** One stored dated closure, neutral of any caller's response or event shape. */
    record ParsedClosure(@NonNull LocalDate date, @Nullable String reason) {}

    /** The {@code operating_hours} column exactly as persisted; also the write path's serialization shape. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record OperatingHoursJsonEntry(String dayOfWeek, String openTime, String closeTime) {}

    /** The {@code holiday_closures} column exactly as persisted; also the write path's serialization shape. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record HolidayClosureJsonEntry(String date, String reason) {}

    /**
     * Read the stored weekly hours, in {@link DayOfWeek} ordinal order.
     *
     * <p>{@code dayOfWeek} is canonicalized case-insensitively against {@link DayOfWeek} because
     * the write path ({@code LocationServiceImpl}) does not validate it today (issue #2020 F1):
     * {@code "MONDAY"}, {@code "Monday"} and {@code "monday"} all persist as-is. Callers are typed
     * on the enum precisely so this drift stops at the wall rather than leaking into every
     * consumer — and so two entries that mean the same day collide and are caught.
     *
     * @return the hours in {@code DayOfWeek} ordinal order, an empty list for {@code "[]"}, or
     *     {@code null} when the column is absent or unreadable
     */
    static @Nullable List<ParsedHours> parseOperatingHours(
            @Nullable String json, @NonNull UUID locationId, @NonNull Logger log) {
        if (json == null) {
            return null;
        }
        List<OperatingHoursJsonEntry> raw;
        try {
            raw = JSON_MAPPER.readValue(json, new TypeReference<List<OperatingHoursJsonEntry>>() {});
        } catch (JsonProcessingException e) {
            log.error("Unparseable operating_hours JSON for location {}: {}", locationId, json, e);
            return null;
        }
        if (raw == null) {
            // The literal JSON `null` parses successfully and yields a null list. Without this the
            // loop below would throw an NPE past every catch in this method, failing the caller
            // instead of degrading to "not configured" the way this parser promises.
            log.error("Null operating_hours JSON literal for location {}", locationId);
            return null;
        }
        // EnumMap always iterates in DayOfWeek ordinal order regardless of insertion order, so this
        // also satisfies the "sorted by DayOfWeek ordinal" contract without a separate sort step.
        Map<DayOfWeek, ParsedHours> byDay = new EnumMap<>(DayOfWeek.class);
        for (OperatingHoursJsonEntry entry : raw) {
            if (entry == null) {
                // A `[null]` element: same NPE-past-every-catch hazard as the literal null above,
                // and not reachable through a catch because even logging it would dereference it.
                log.error("Null operating_hours entry for location {}", locationId);
                return null;
            }
            DayOfWeek day;
            try {
                day = DayOfWeek.valueOf(entry.dayOfWeek().trim().toUpperCase(Locale.ROOT));
            } catch (RuntimeException e) {
                log.error("Unparseable operating_hours dayOfWeek={} for location {}", entry.dayOfWeek(), locationId, e);
                return null;
            }
            if (byDay.containsKey(day)) {
                log.error(
                        "Duplicate operating_hours entry for day {} on location {} after canonicalizing"
                                + " dayOfWeek values; publishing operatingHours=null rather than a partial week",
                        day,
                        locationId);
                return null;
            }
            try {
                byDay.put(
                        day,
                        new ParsedHours(
                                day,
                                entry.openTime() == null ? null : LocalTime.parse(entry.openTime()),
                                entry.closeTime() == null ? null : LocalTime.parse(entry.closeTime())));
            } catch (RuntimeException e) {
                log.error(
                        "Unparseable operating_hours openTime/closeTime for day {} on location {}: {}",
                        day,
                        locationId,
                        entry,
                        e);
                return null;
            }
        }
        return List.copyOf(byDay.values());
    }

    /**
     * Read the stored dated closures, in date order.
     *
     * @return the closures sorted by date, an empty list for {@code "[]"}, or {@code null} when the
     *     column is absent or unreadable
     */
    static @Nullable List<ParsedClosure> parseHolidayClosures(
            @Nullable String json, @NonNull UUID locationId, @NonNull Logger log) {
        if (json == null) {
            return null;
        }
        List<HolidayClosureJsonEntry> raw;
        try {
            raw = JSON_MAPPER.readValue(json, new TypeReference<List<HolidayClosureJsonEntry>>() {});
        } catch (JsonProcessingException e) {
            log.error("Unparseable holiday_closures JSON for location {}: {}", locationId, json, e);
            return null;
        }
        if (raw == null) {
            // See parseOperatingHours: the literal JSON `null` parses to a null list.
            log.error("Null holiday_closures JSON literal for location {}", locationId);
            return null;
        }
        List<ParsedClosure> parsed = new ArrayList<>(raw.size());
        for (HolidayClosureJsonEntry entry : raw) {
            if (entry == null) {
                log.error("Null holiday_closures entry for location {}", locationId);
                return null;
            }
            try {
                parsed.add(new ParsedClosure(LocalDate.parse(entry.date()), entry.reason()));
            } catch (RuntimeException e) {
                log.error("Unparseable holiday_closures entry {} for location {}", entry, locationId, e);
                return null;
            }
        }
        parsed.sort(Comparator.comparing(ParsedClosure::date));
        return List.copyOf(parsed);
    }
}
