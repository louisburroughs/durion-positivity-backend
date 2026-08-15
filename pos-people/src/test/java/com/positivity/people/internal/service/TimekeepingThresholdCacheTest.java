package com.positivity.people.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.dto.AttendanceReportKey;
import com.positivity.people.internal.entity.TimekeepingPolicy;
import com.positivity.people.internal.enums.TimekeepingPolicyScopeType;
import com.positivity.people.internal.repository.TimekeepingPolicyRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Threshold resolution for the attendance discrepancy report: location policy beats global,
 * global beats the built-in default, and preloading is what keeps the per-row lookup off the
 * database.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TimekeepingThresholdCache")
class TimekeepingThresholdCacheTest {

    private static final UUID LOCATION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4c01");
    private static final UUID OTHER_LOCATION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4c02");
    private static final LocalDate REPORT_DATE = LocalDate.of(2026, 3, 2);
    private static final ZoneId ZONE = ZoneId.of("America/New_York");
    private static final int DEFAULT_THRESHOLD_MINUTES = 30;

    @Mock
    private TimekeepingPolicyRepository timekeepingPolicyRepository;

    private static TimekeepingPolicy policy(
            TimekeepingPolicyScopeType scopeType, UUID scopeId, Integer thresholdMinutes) {
        TimekeepingPolicy policy = new TimekeepingPolicy();
        policy.setTimekeepingPolicyId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4cff"));
        policy.setScopeType(scopeType);
        policy.setScopeId(scopeId);
        policy.setJobTimeDiscrepancyThresholdMinutes(thresholdMinutes);
        setUpdatedAt(policy, Instant.parse("2026-01-01T00:00:00Z"));
        return policy;
    }

    /**
     * {@code updatedAt} is owned by {@code @LastModifiedDate} auditing, which writes the field
     * reflectively and exposes no setter, so a test that needs a specific audit stamp has to write
     * the field the same way.
     */
    private static void setUpdatedAt(TimekeepingPolicy policy, Instant updatedAt) {
        ReflectionTestUtils.setField(policy, "updatedAt", updatedAt);
    }

    private static AttendanceReportKey key(String locationId) {
        return new AttendanceReportKey("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4cee", locationId, REPORT_DATE);
    }

    private TimekeepingThresholdCache cache(boolean preloadLocations, boolean preloadGlobals) {
        return new TimekeepingThresholdCache(timekeepingPolicyRepository, 4096, preloadLocations, preloadGlobals);
    }

    @Nested
    @DisplayName("resolveThresholdMinutes")
    class ResolveThresholdMinutes {

        @Test
        void prefersTheLocationPolicyOverTheGlobalOne() {
            when(timekeepingPolicyRepository.findByScopeType(TimekeepingPolicyScopeType.GLOBAL))
                    .thenReturn(List.of(policy(TimekeepingPolicyScopeType.GLOBAL, null, 45)));
            when(timekeepingPolicyRepository.findByScopeTypeAndScopeIdIn(
                            TimekeepingPolicyScopeType.LOCATION, Set.of(LOCATION_ID)))
                    .thenReturn(List.of(policy(TimekeepingPolicyScopeType.LOCATION, LOCATION_ID, 15)));

            var context = cache(true, true).createContext(Set.of(key(LOCATION_ID.toString())), ZONE);

            assertThat(context.resolveThresholdMinutes(LOCATION_ID.toString(), REPORT_DATE))
                    .isEqualTo(15);
        }

        @Test
        void fallsBackToTheGlobalPolicyWhenTheLocationHasNone() {
            when(timekeepingPolicyRepository.findByScopeType(TimekeepingPolicyScopeType.GLOBAL))
                    .thenReturn(List.of(policy(TimekeepingPolicyScopeType.GLOBAL, null, 45)));
            when(timekeepingPolicyRepository.findByScopeTypeAndScopeIdIn(
                            TimekeepingPolicyScopeType.LOCATION, Set.of(LOCATION_ID)))
                    .thenReturn(List.of());

            var context = cache(true, true).createContext(Set.of(key(LOCATION_ID.toString())), ZONE);

            assertThat(context.resolveThresholdMinutes(LOCATION_ID.toString(), REPORT_DATE))
                    .isEqualTo(45);
        }

        @Test
        void fallsBackToTheBuiltInDefaultWhenNoPolicyApplies() {
            var context = cache(true, true).createContext(Set.of(key(LOCATION_ID.toString())), ZONE);

            assertThat(context.resolveThresholdMinutes(LOCATION_ID.toString(), REPORT_DATE))
                    .isEqualTo(DEFAULT_THRESHOLD_MINUTES);
        }

        @Test
        void ignoresANegativeOrMissingThresholdOnAnOtherwiseEffectivePolicy() {
            when(timekeepingPolicyRepository.findByScopeTypeAndScopeIdIn(
                            TimekeepingPolicyScopeType.LOCATION, Set.of(LOCATION_ID)))
                    .thenReturn(List.of(policy(TimekeepingPolicyScopeType.LOCATION, LOCATION_ID, -5)));

            var context = cache(true, true).createContext(Set.of(key(LOCATION_ID.toString())), ZONE);

            assertThat(context.resolveThresholdMinutes(LOCATION_ID.toString(), REPORT_DATE))
                    .isEqualTo(DEFAULT_THRESHOLD_MINUTES);
        }

        @Test
        void ignoresAGlobalPolicyCarryingNoThreshold() {
            when(timekeepingPolicyRepository.findByScopeType(TimekeepingPolicyScopeType.GLOBAL))
                    .thenReturn(List.of(policy(TimekeepingPolicyScopeType.GLOBAL, null, null)));

            var context = cache(true, true).createContext(Set.of(key(LOCATION_ID.toString())), ZONE);

            assertThat(context.resolveThresholdMinutes(LOCATION_ID.toString(), REPORT_DATE))
                    .isEqualTo(DEFAULT_THRESHOLD_MINUTES);
        }

        @Test
        void skipsAPolicyWhoseEffectiveWindowDoesNotCoverTheReportDate() {
            TimekeepingPolicy expired = policy(TimekeepingPolicyScopeType.LOCATION, LOCATION_ID, 5);
            expired.setEffectiveEndAt(Instant.parse("2026-01-31T00:00:00Z"));
            TimekeepingPolicy notYetStarted = policy(TimekeepingPolicyScopeType.LOCATION, LOCATION_ID, 7);
            notYetStarted.setEffectiveStartAt(Instant.parse("2026-12-01T00:00:00Z"));
            when(timekeepingPolicyRepository.findByScopeTypeAndScopeIdIn(
                            TimekeepingPolicyScopeType.LOCATION, Set.of(LOCATION_ID)))
                    .thenReturn(List.of(expired, notYetStarted));

            var context = cache(true, true).createContext(Set.of(key(LOCATION_ID.toString())), ZONE);

            assertThat(context.resolveThresholdMinutes(LOCATION_ID.toString(), REPORT_DATE))
                    .isEqualTo(DEFAULT_THRESHOLD_MINUTES);
        }

        @Test
        void picksTheMostRecentlyUpdatedEffectivePolicy() {
            TimekeepingPolicy older = policy(TimekeepingPolicyScopeType.LOCATION, LOCATION_ID, 10);
            setUpdatedAt(older, Instant.parse("2026-01-01T00:00:00Z"));
            TimekeepingPolicy newer = policy(TimekeepingPolicyScopeType.LOCATION, LOCATION_ID, 20);
            setUpdatedAt(newer, Instant.parse("2026-02-01T00:00:00Z"));
            when(timekeepingPolicyRepository.findByScopeTypeAndScopeIdIn(
                            TimekeepingPolicyScopeType.LOCATION, Set.of(LOCATION_ID)))
                    .thenReturn(List.of(older, newer));

            var context = cache(true, true).createContext(Set.of(key(LOCATION_ID.toString())), ZONE);

            assertThat(context.resolveThresholdMinutes(LOCATION_ID.toString(), REPORT_DATE))
                    .isEqualTo(20);
        }

        @Test
        void breaksATieOnUpdatedAtWithTheLatestEffectiveStart() {
            TimekeepingPolicy earlierStart = policy(TimekeepingPolicyScopeType.LOCATION, LOCATION_ID, 10);
            earlierStart.setEffectiveStartAt(Instant.parse("2026-01-01T00:00:00Z"));
            TimekeepingPolicy laterStart = policy(TimekeepingPolicyScopeType.LOCATION, LOCATION_ID, 20);
            laterStart.setEffectiveStartAt(Instant.parse("2026-02-01T00:00:00Z"));
            when(timekeepingPolicyRepository.findByScopeTypeAndScopeIdIn(
                            TimekeepingPolicyScopeType.LOCATION, Set.of(LOCATION_ID)))
                    .thenReturn(List.of(earlierStart, laterStart));

            var context = cache(true, true).createContext(Set.of(key(LOCATION_ID.toString())), ZONE);

            assertThat(context.resolveThresholdMinutes(LOCATION_ID.toString(), REPORT_DATE))
                    .isEqualTo(20);
        }

        @Test
        void treatsANonUuidLocationAsGlobalOnly() {
            when(timekeepingPolicyRepository.findByScopeType(TimekeepingPolicyScopeType.GLOBAL))
                    .thenReturn(List.of(policy(TimekeepingPolicyScopeType.GLOBAL, null, 45)));

            var context = cache(true, true).createContext(Set.of(key("not-a-uuid")), ZONE);

            assertThat(context.resolveThresholdMinutes("not-a-uuid", REPORT_DATE))
                    .isEqualTo(45);
            // No location ids to preload means no location query at all.
            verify(timekeepingPolicyRepository, never())
                    .findByScopeTypeAndScopeIdIn(
                            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }

        @Test
        void answersRepeatedLookupsFromItsOwnCache() {
            when(timekeepingPolicyRepository.findByScopeType(TimekeepingPolicyScopeType.GLOBAL))
                    .thenReturn(List.of(policy(TimekeepingPolicyScopeType.GLOBAL, null, 45)));

            var context = cache(false, false).createContext(Set.of(key(LOCATION_ID.toString())), ZONE);

            assertThat(context.resolveThresholdMinutes(LOCATION_ID.toString(), REPORT_DATE))
                    .isEqualTo(45);
            assertThat(context.resolveThresholdMinutes(LOCATION_ID.toString(), REPORT_DATE))
                    .isEqualTo(45);

            // Preloading is off, so the first miss queries; the second call must not.
            verify(timekeepingPolicyRepository, times(1))
                    .findByScopeTypeAndScopeId(TimekeepingPolicyScopeType.LOCATION, LOCATION_ID);
            verify(timekeepingPolicyRepository, times(1)).findByScopeType(TimekeepingPolicyScopeType.GLOBAL);
        }

        @Test
        void evictsTheEldestEntryOnceTheCacheIsFull() {
            TimekeepingThresholdCache oneEntryCache =
                    new TimekeepingThresholdCache(timekeepingPolicyRepository, 1, false, false);
            var context = oneEntryCache.createContext(Set.of(key(LOCATION_ID.toString())), ZONE);

            context.resolveThresholdMinutes(LOCATION_ID.toString(), REPORT_DATE);
            context.resolveThresholdMinutes(OTHER_LOCATION_ID.toString(), REPORT_DATE);
            context.resolveThresholdMinutes(LOCATION_ID.toString(), REPORT_DATE);

            // The first location was evicted by the second, so it is queried twice.
            verify(timekeepingPolicyRepository, times(2))
                    .findByScopeTypeAndScopeId(TimekeepingPolicyScopeType.LOCATION, LOCATION_ID);
        }

        @Test
        void treatsANonPositiveCacheSizeAsTheBuiltInMaximum() {
            TimekeepingThresholdCache zeroSized =
                    new TimekeepingThresholdCache(timekeepingPolicyRepository, 0, false, false);
            var context = zeroSized.createContext(Set.of(key(LOCATION_ID.toString())), ZONE);

            context.resolveThresholdMinutes(LOCATION_ID.toString(), REPORT_DATE);
            context.resolveThresholdMinutes(OTHER_LOCATION_ID.toString(), REPORT_DATE);
            context.resolveThresholdMinutes(LOCATION_ID.toString(), REPORT_DATE);

            verify(timekeepingPolicyRepository, times(1))
                    .findByScopeTypeAndScopeId(TimekeepingPolicyScopeType.LOCATION, LOCATION_ID);
        }
    }

    @Nested
    @DisplayName("createContext")
    class CreateContext {

        @Test
        void groupsPreloadedLocationPoliciesByScopeAndDropsScopelessRows() {
            TimekeepingPolicy scopeless = policy(TimekeepingPolicyScopeType.LOCATION, null, 5);
            when(timekeepingPolicyRepository.findByScopeTypeAndScopeIdIn(
                            TimekeepingPolicyScopeType.LOCATION, Set.of(LOCATION_ID, OTHER_LOCATION_ID)))
                    .thenReturn(List.of(scopeless, policy(TimekeepingPolicyScopeType.LOCATION, LOCATION_ID, 12)));

            var context = cache(true, true)
                    .createContext(Set.of(key(LOCATION_ID.toString()), key(OTHER_LOCATION_ID.toString())), ZONE);

            assertThat(context.resolveThresholdMinutes(LOCATION_ID.toString(), REPORT_DATE))
                    .isEqualTo(12);
            assertThat(context.resolveThresholdMinutes(OTHER_LOCATION_ID.toString(), REPORT_DATE))
                    .isEqualTo(DEFAULT_THRESHOLD_MINUTES);
        }

        @Test
        void queriesNothingUpFrontWhenPreloadingIsDisabled() {
            cache(false, false).createContext(Set.of(key(LOCATION_ID.toString())), ZONE);

            verifyNoInteractions(timekeepingPolicyRepository);
        }

        @Test
        void skipsTheLocationPreloadWhenNoKeyCarriesAUuidLocation() {
            cache(true, false).createContext(Set.of(key("not-a-uuid")), ZONE);

            verifyNoInteractions(timekeepingPolicyRepository);
        }
    }
}
