package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.entity.ExtStaffingAssignmentReplica;
import com.positivity.workorder.internal.repository.ExtPersonReplicaRepository;
import com.positivity.workorder.internal.repository.ExtStaffingAssignmentReplicaRepository;
import com.positivity.workorder.internal.repository.ExtUserLinkReplicaRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link PeopleAvailabilityLocalService#isEligibleAtSite} (#1990) — the single definition of
 * "staffed at a site" shared by the site roster this service already served and the technician
 * site-eligibility check {@code TechnicianAssignmentServiceImpl} now enforces.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PeopleAvailabilityLocalService.isEligibleAtSite")
class PeopleAvailabilityLocalServiceTest {

    private static final UUID PERSON_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f3301");
    private static final UUID SITE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f3302");
    private static final UUID OTHER_SITE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f3303");
    private static final LocalDate TODAY = LocalDate.parse("2026-03-01");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-01T12:00:00Z"), ZoneOffset.UTC);
    private static final String ACTIVE = "ACTIVE";

    @Mock
    private ExtStaffingAssignmentReplicaRepository assignmentReplicaRepository;

    @Mock
    private ExtPersonReplicaRepository personReplicaRepository;

    @Mock
    private ExtUserLinkReplicaRepository linkReplicaRepository;

    private PeopleAvailabilityLocalService service;

    @BeforeEach
    void setUp() {
        service = new PeopleAvailabilityLocalService(
                CLOCK, assignmentReplicaRepository, personReplicaRepository, linkReplicaRepository);
    }

    private static ExtStaffingAssignmentReplica row(UUID locationId, boolean primary, LocalDate from, LocalDate to) {
        return ExtStaffingAssignmentReplica.builder()
                .assignmentId(UUID.randomUUID())
                .employeeId(UUID.randomUUID())
                .personId(PERSON_ID)
                .locationId(locationId)
                .status(ACTIVE)
                .primary(primary)
                .effectiveFrom(from)
                .effectiveTo(to)
                .aggregateVersion(1L)
                .updatedAt(Instant.EPOCH)
                .build();
    }

    @Nested
    @DisplayName("#1990: a technician with no ACTIVE staffing at all is allowed")
    class NoActiveStaffing {

        @Test
        @DisplayName("no rows at all — replica lag, bootstrap and DLQ must not take a shop offline")
        void noRowsIsEligible() {
            when(assignmentReplicaRepository.findByPersonIdAndStatus(PERSON_ID, ACTIVE))
                    .thenReturn(List.of());

            assertThat(service.isEligibleAtSite(PERSON_ID, SITE_ID, TODAY)).isTrue();
        }

        @Test
        @DisplayName("a row exists but is not yet effective — treated the same as no active staffing")
        void notYetEffectiveIsEligible() {
            when(assignmentReplicaRepository.findByPersonIdAndStatus(PERSON_ID, ACTIVE))
                    .thenReturn(List.of(row(OTHER_SITE_ID, true, TODAY.plusDays(1), null)));

            assertThat(service.isEligibleAtSite(PERSON_ID, SITE_ID, TODAY)).isTrue();
        }

        @Test
        @DisplayName("a row exists but has already ended — treated the same as no active staffing")
        void alreadyEndedIsEligible() {
            when(assignmentReplicaRepository.findByPersonIdAndStatus(PERSON_ID, ACTIVE))
                    .thenReturn(List.of(row(OTHER_SITE_ID, true, TODAY.minusDays(30), TODAY.minusDays(1))));

            assertThat(service.isEligibleAtSite(PERSON_ID, SITE_ID, TODAY)).isTrue();
        }
    }

    @Nested
    @DisplayName("a technician staffed elsewhere and nowhere else is refused")
    class StaffedElsewhere {

        @Test
        @DisplayName("one ACTIVE row, effective today, at a different site")
        void singleRowAtOtherSite() {
            when(assignmentReplicaRepository.findByPersonIdAndStatus(PERSON_ID, ACTIVE))
                    .thenReturn(List.of(row(OTHER_SITE_ID, true, null, null)));

            assertThat(service.isEligibleAtSite(PERSON_ID, SITE_ID, TODAY)).isFalse();
        }

        @Test
        @DisplayName("several ACTIVE rows, none of them at the target site")
        void multipleRowsAllElsewhere() {
            UUID thirdSite = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f3304");
            when(assignmentReplicaRepository.findByPersonIdAndStatus(PERSON_ID, ACTIVE))
                    .thenReturn(List.of(row(OTHER_SITE_ID, false, null, null), row(thirdSite, true, null, null)));

            assertThat(service.isEligibleAtSite(PERSON_ID, SITE_ID, TODAY)).isFalse();
        }
    }

    @Nested
    @DisplayName("a technician staffed at the target site is eligible")
    class StaffedHere {

        @Test
        @DisplayName("is_primary is not considered — a non-primary row at the site is enough")
        void nonPrimaryRowAtSiteIsEnough() {
            when(assignmentReplicaRepository.findByPersonIdAndStatus(PERSON_ID, ACTIVE))
                    .thenReturn(List.of(row(SITE_ID, false, null, null)));

            assertThat(service.isEligibleAtSite(PERSON_ID, SITE_ID, TODAY)).isTrue();
        }

        @Test
        @DisplayName("staffed at more than one site, one of which is the target, is enough")
        void oneOfSeveralSitesIsEnough() {
            when(assignmentReplicaRepository.findByPersonIdAndStatus(PERSON_ID, ACTIVE))
                    .thenReturn(List.of(row(OTHER_SITE_ID, true, null, null), row(SITE_ID, false, null, null)));

            assertThat(service.isEligibleAtSite(PERSON_ID, SITE_ID, TODAY)).isTrue();
        }

        @Test
        @DisplayName("effective from today through no end date is eligible")
        void effectiveFromTodayOpenEnded() {
            when(assignmentReplicaRepository.findByPersonIdAndStatus(PERSON_ID, ACTIVE))
                    .thenReturn(List.of(row(SITE_ID, true, TODAY, null)));

            assertThat(service.isEligibleAtSite(PERSON_ID, SITE_ID, TODAY)).isTrue();
        }

        @Test
        @DisplayName("effective through today (inclusive end date) is eligible")
        void effectiveThroughTodayInclusive() {
            when(assignmentReplicaRepository.findByPersonIdAndStatus(PERSON_ID, ACTIVE))
                    .thenReturn(List.of(row(SITE_ID, true, TODAY.minusDays(10), TODAY)));

            assertThat(service.isEligibleAtSite(PERSON_ID, SITE_ID, TODAY)).isTrue();
        }
    }

    @Test
    @DisplayName("queries only ACTIVE rows for the technician being checked")
    void queriesByPersonAndActiveStatus() {
        when(assignmentReplicaRepository.findByPersonIdAndStatus(any(), any())).thenReturn(List.of());

        service.isEligibleAtSite(PERSON_ID, SITE_ID, TODAY);

        org.mockito.Mockito.verify(assignmentReplicaRepository).findByPersonIdAndStatus(PERSON_ID, ACTIVE);
    }
}
