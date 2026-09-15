package com.positivity.domainevents.location;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a location (site/shop) was created or changed (ADR-0044 §6, issue #890 Phase 4.2).
 *
 * <p>Published by pos-location on {@code location.events.v1} with
 * {@code eventType = "location.location.updated"}. Consumers maintain read-only
 * {@code ext_location} replicas serving rosters (pos-inventory, pos-people), site defaults
 * (pos-inventory) and the tax destination address (pos-invoice, pos-workorder).
 *
 * <p>The address fields are the tax-jurisdiction inputs previously served by
 * {@code GET /v1/locations/{id}} — ADR-0044 explicitly reversed the older "never replicate
 * address data" rule for this feed; consumers must still run ADR-0021 address validation on
 * replica data before tax calculation.
 *
 * @param locationId location identifier (also the envelope aggregateId)
 * @param name display name
 * @param code unique short code
 * @param status owner status string, e.g. {@code ACTIVE}
 * @param active convenience flag mirroring the owner's {@code is_active} column
 * @param locationType location type name (null when untyped)
 * @param hrLocationId external HR system location reference
 * @param timezone IANA timezone id
 * @param addressLine1 street address line 1
 * @param addressLine2 street address line 2
 * @param city city
 * @param region state/province code
 * @param postalCode postal code (required for tax jurisdiction determination)
 * @param country country code (required for tax jurisdiction determination)
 * @param defaultStagingLocationId site default staging storage location
 * @param defaultQuarantineLocationId site default quarantine storage location
 * @param parents typed parent edges of this location in the owner's hierarchy (issue #892 —
 *     consumers rebuild descendant queries over the replica; null on events emitted before
 *     this field existed, empty when the location has no parents)
 * @param operatingHours the location's weekly opening windows, at most one per day-of-week and
 *     sorted by {@link DayOfWeek} ordinal (issue #2023). A day with no entry is closed.
 *     {@code null} means <em>not configured</em> — which is not the same fact as an empty list,
 *     meaning <em>configured as closed every day</em> (DECISION-LOCATION-004). Also null on
 *     events emitted before this field existed.
 * @param holidayClosures dated closures, sorted by date (issue #2023). Same null-versus-empty
 *     distinction as {@code operatingHours} (DECISION-LOCATION-005).
 * @param checkInBufferMinutes minutes reserved before an appointment for check-in, null when
 *     not configured
 * @param cleanupBufferMinutes minutes reserved after an appointment for cleanup, null when not
 *     configured
 * @param createdAt owner row creation timestamp
 * @param updatedAt owner row last-update timestamp
 */
public record LocationUpdatedV1(
        @NonNull UUID locationId,
        @Nullable String name,
        @Nullable String code,
        @Nullable String status,
        boolean active,
        @Nullable String locationType,
        @Nullable String hrLocationId,
        @Nullable String timezone,
        @Nullable String addressLine1,
        @Nullable String addressLine2,
        @Nullable String city,
        @Nullable String region,
        @Nullable String postalCode,
        @Nullable String country,
        @Nullable UUID defaultStagingLocationId,
        @Nullable UUID defaultQuarantineLocationId,
        @Nullable List<ParentRef> parents,
        @Nullable List<OperatingHoursEntry> operatingHours,
        @Nullable List<HolidayClosure> holidayClosures,
        @Nullable Integer checkInBufferMinutes,
        @Nullable Integer cleanupBufferMinutes,
        @Nullable Instant createdAt,
        @Nullable Instant updatedAt) {

    public static final String EVENT_TYPE = "location.location.updated";
    public static final int SCHEMA_VERSION = 1;

    /**
     * One typed parent edge in the location hierarchy.
     *
     * @param parentId the parent location id
     * @param parentType the owner's {@code ParentType} name, e.g. {@code PHYSICAL}
     */
    public record ParentRef(@NonNull UUID parentId, @NonNull String parentType) {}

    /**
     * One weekly opening window.
     *
     * <p>{@code dayOfWeek} is the {@link DayOfWeek} enum rather than a string so the contract
     * itself forbids the {@code "Monday"} / {@code "MONDAY"} / {@code "Mon"} drift the owner's
     * write path still permits (issue #2020 F1). The publisher canonicalizes on the way out and
     * fails loudly on a value it cannot parse rather than dropping the day.
     *
     * <p>The stored shape is one window per day: no split shifts and no overnight ranges.
     *
     * @param dayOfWeek the day this window applies to
     * @param openTime local opening time in the location's {@code timezone}
     * @param closeTime local closing time in the location's {@code timezone}, after {@code openTime}
     */
    public record OperatingHoursEntry(
            @NonNull DayOfWeek dayOfWeek,
            @Nullable LocalTime openTime,
            @Nullable LocalTime closeTime) {

        public OperatingHoursEntry {
            if (dayOfWeek == null) {
                throw new IllegalArgumentException("dayOfWeek must not be null");
            }
        }
    }

    /**
     * One dated closure.
     *
     * @param date the closed date
     * @param reason why the location is closed, null when none was recorded
     */
    public record HolidayClosure(
            @NonNull LocalDate date, @Nullable String reason) {

        public HolidayClosure {
            if (date == null) {
                throw new IllegalArgumentException("date must not be null");
            }
        }
    }

    public LocationUpdatedV1 {
        if (locationId == null) {
            throw new IllegalArgumentException("locationId must not be null");
        }
    }
}
