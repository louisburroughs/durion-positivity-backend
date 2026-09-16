package com.positivity.shopmanager.internal.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Reads the operating-hours facts {@code ext_location} mirrors verbatim from pos-location (#2023):
 * the timezone, the weekly windows and the dated closures. Shared by the capacity read and the
 * conflict evaluator (CAP-326, spec D18.1) so there is one reading of the owner's JSON, not two.
 *
 * <p>All-or-nothing on the weekly windows, mirroring the producer's own rule: a payload with one
 * unreadable entry is {@code null} — unknown — rather than a partial week in which the unreadable
 * day would silently read as closed. Closures degrade to "none known" instead, because they are
 * supplementary to an otherwise-assemblable day, not a precondition for it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocationHoursParser {

    private final ObjectMapper objectMapper;

    /** Raw shape of one {@code operating_hours} array element, deserialized as strings only. */
    public record RawOperatingHoursEntry(String dayOfWeek, String openTime, String closeTime) {}

    /** Raw shape of one {@code holiday_closures} array element, deserialized as strings only. */
    public record RawHolidayClosure(String date, String reason) {}

    /** Null when the replica carries no timezone, or one {@link ZoneId} does not recognise. */
    public @Nullable ZoneId parseZone(UUID locationId, @Nullable String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return null;
        }
        try {
            return ZoneId.of(timezone);
        } catch (Exception e) {
            log.warn("Location {} has unrecognised timezone '{}'", locationId, timezone);
            return null;
        }
    }

    /**
     * @return {@code null} when the JSON cannot be parsed at all, or any one entry's {@code
     *     dayOfWeek} cannot be resolved (distinct from an empty, valid list) so the caller treats the
     *     hours as unknown rather than asserting a closure the data does not actually confirm
     */
    public @Nullable Map<DayOfWeek, RawOperatingHoursEntry> parseOperatingHours(UUID locationId, String json) {
        try {
            List<RawOperatingHoursEntry> entries =
                    objectMapper.readValue(json, new TypeReference<List<RawOperatingHoursEntry>>() {});
            Map<DayOfWeek, RawOperatingHoursEntry> byDayOfWeek = new EnumMap<>(DayOfWeek.class);
            for (RawOperatingHoursEntry entry : entries) {
                if (entry == null || entry.dayOfWeek() == null) {
                    log.warn(
                            "Location {} has an operatingHours entry with no dayOfWeek; treating the whole payload"
                                    + " as unparsable so no weekday is silently reported CLOSED",
                            locationId);
                    return null;
                }
                try {
                    byDayOfWeek.put(DayOfWeek.valueOf(entry.dayOfWeek()), entry);
                } catch (IllegalArgumentException e) {
                    log.warn(
                            "Location {} operatingHours entry has an unrecognised dayOfWeek '{}'; treating the whole"
                                    + " payload as unparsable so that weekday is UNAVAILABLE, never CLOSED",
                            locationId,
                            entry.dayOfWeek());
                    return null;
                }
            }
            return byDayOfWeek;
        } catch (Exception e) {
            log.warn("Location {} has unparsable operatingHours; treating them as unknown", locationId, e);
            return null;
        }
    }

    /** Date-keyed closure reasons; a malformed payload degrades to "no closures known". */
    public Map<LocalDate, String> parseHolidayClosures(UUID locationId, @Nullable String json) {
        if (json == null) {
            return Map.of();
        }
        try {
            List<RawHolidayClosure> entries =
                    objectMapper.readValue(json, new TypeReference<List<RawHolidayClosure>>() {});
            Map<LocalDate, String> byDate = new HashMap<>();
            for (RawHolidayClosure entry : entries) {
                if (entry == null || entry.date() == null) {
                    continue;
                }
                byDate.put(LocalDate.parse(entry.date()), entry.reason());
            }
            return byDate;
        } catch (Exception e) {
            log.warn("Location {} has unparsable holidayClosures; treating the range as having none", locationId, e);
            return Map.of();
        }
    }
}
