package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.dto.PeopleAvailabilityResponse;
import com.positivity.workorder.internal.entity.ExtEmployeeReplica;
import com.positivity.workorder.internal.entity.ExtPersonCredentialReplica;
import com.positivity.workorder.internal.entity.ExtPersonReplica;
import com.positivity.workorder.internal.entity.ExtStaffingAssignmentReplica;
import com.positivity.workorder.internal.repository.ExtEmployeeReplicaRepository;
import com.positivity.workorder.internal.repository.ExtPersonCredentialReplicaRepository;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @Mock
    private ExtEmployeeReplicaRepository employeeReplicaRepository;

    @Mock
    private ExtPersonCredentialReplicaRepository credentialReplicaRepository;

    private PeopleAvailabilityLocalService service;

    @BeforeEach
    void setUp() {
        service = new PeopleAvailabilityLocalService(
                CLOCK,
                assignmentReplicaRepository,
                personReplicaRepository,
                linkReplicaRepository,
                employeeReplicaRepository,
                credentialReplicaRepository);
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

    private static ExtEmployeeReplica employee(String status, Instant statusEffectiveAt, Instant updatedAt) {
        return employee(status, statusEffectiveAt, 1L, updatedAt);
    }

    private static ExtEmployeeReplica employee(
            String status, Instant statusEffectiveAt, long aggregateVersion, Instant updatedAt) {
        return ExtEmployeeReplica.builder()
                .employeeId(UUID.randomUUID())
                .personId(PERSON_ID)
                .status(status)
                .statusEffectiveAt(statusEffectiveAt)
                .aggregateVersion(aggregateVersion)
                .updatedAt(updatedAt)
                .build();
    }

    @Nested
    @DisplayName("#2120: an offboarded technician is never eligible")
    class InactiveEmployment {

        @ParameterizedTest
        @ValueSource(strings = {"TERMINATED", "DISABLED", "SUSPENDED"})
        @DisplayName("inactive status is refused even with no staffing rows at all")
        void inactiveIsNotEligibleWithoutStaffingRows(String status) {
            when(employeeReplicaRepository.findByPersonId(PERSON_ID))
                    .thenReturn(List.of(employee(status, Instant.parse("2026-02-01T00:00:00Z"), Instant.EPOCH)));
            // No staffing rows stubbed: the empty default is exactly the case that would otherwise pass.

            assertThat(service.isEligibleAtSite(PERSON_ID, SITE_ID, TODAY)).isFalse();
        }

        @Test
        @DisplayName("inactive status is refused before staffing is consulted at all")
        void inactiveIsNotEligibleAtStaffedSite() {
            when(employeeReplicaRepository.findByPersonId(PERSON_ID))
                    .thenReturn(List.of(employee("TERMINATED", null, Instant.EPOCH)));

            assertThat(service.isEligibleAtSite(PERSON_ID, SITE_ID, TODAY)).isFalse();
            org.mockito.Mockito.verifyNoInteractions(assignmentReplicaRepository);
        }

        @ParameterizedTest
        @ValueSource(strings = {"ACTIVE", "ON_LEAVE"})
        @DisplayName("ACTIVE and ON_LEAVE keep the no-staffing softening")
        void employedStatusesStayEligible(String status) {
            when(employeeReplicaRepository.findByPersonId(PERSON_ID))
                    .thenReturn(List.of(employee(status, null, Instant.EPOCH)));
            when(assignmentReplicaRepository.findByPersonIdAndStatus(PERSON_ID, ACTIVE))
                    .thenReturn(List.of());

            assertThat(service.isEligibleAtSite(PERSON_ID, SITE_ID, TODAY)).isTrue();
            assertThat(service.inactiveEmploymentStatus(PERSON_ID)).isEmpty();
        }

        @Test
        @DisplayName("a rehire (later ACTIVE row) supersedes an earlier TERMINATED row")
        void latestRowWins() {
            when(employeeReplicaRepository.findByPersonId(PERSON_ID))
                    .thenReturn(List.of(
                            employee("TERMINATED", Instant.parse("2025-06-01T00:00:00Z"), Instant.EPOCH),
                            employee("ACTIVE", Instant.parse("2026-01-01T00:00:00Z"), Instant.EPOCH)));

            assertThat(service.inactiveEmploymentStatus(PERSON_ID)).isEmpty();
        }

        @Test
        @DisplayName("statusEffectiveAt falls back to the fact's emission time when it carried none")
        void fallsBackToEmissionTime() {
            when(employeeReplicaRepository.findByPersonId(PERSON_ID))
                    .thenReturn(List.of(
                            employee(
                                    "ACTIVE",
                                    null,
                                    Instant.parse("2025-06-01T00:00:00Z").toEpochMilli(),
                                    Instant.EPOCH),
                            employee(
                                    "DISABLED",
                                    null,
                                    Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(),
                                    Instant.EPOCH)));

            assertThat(service.inactiveEmploymentStatus(PERSON_ID)).contains("DISABLED");
        }

        @Test
        @DisplayName("a replayed TERMINATED fact without statusEffectiveAt does not outrank a dated ACTIVE rehire")
        void replayedUndatedTerminationDoesNotOutrankADatedRehire() {
            // The replica's updatedAt is stamped at ingest, so the replayed row is the freshest by that
            // column; ordering on it would lock the person out until pos-people re-emitted ACTIVE.
            when(employeeReplicaRepository.findByPersonId(PERSON_ID))
                    .thenReturn(List.of(
                            employee(
                                    "ACTIVE",
                                    Instant.parse("2026-01-01T00:00:00Z"),
                                    Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(),
                                    Instant.EPOCH),
                            employee(
                                    "TERMINATED",
                                    null,
                                    Instant.parse("2025-06-01T00:00:00Z").toEpochMilli(),
                                    Instant.parse("2026-09-30T00:00:00Z"))));

            assertThat(service.inactiveEmploymentStatus(PERSON_ID)).isEmpty();
        }
    }

    @Nested
    @DisplayName("#2119: fetchAvailability roster")
    class Roster {

        private static final UUID KEPT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f3310");

        private ExtStaffingAssignmentReplica assignmentFor(UUID personId) {
            ExtStaffingAssignmentReplica a = row(SITE_ID, true, null, null);
            a.setPersonId(personId);
            return a;
        }

        @Test
        @DisplayName("excludes a TERMINATED person, keeps one with no employee row")
        void terminatedExcludedNoRowKept() {
            when(assignmentReplicaRepository.findByLocationIdAndStatus(SITE_ID, ACTIVE))
                    .thenReturn(List.of(assignmentFor(PERSON_ID), assignmentFor(KEPT_ID)));
            when(employeeReplicaRepository.findByPersonIdIn(any()))
                    .thenReturn(List.of(employee("TERMINATED", Instant.parse("2026-02-01T00:00:00Z"), Instant.EPOCH)));
            when(personReplicaRepository.findByPersonIdIn(any()))
                    .thenReturn(List.of(ExtPersonReplica.builder()
                            .personId(KEPT_ID)
                            .firstName("Kept")
                            .lastName("Tech")
                            .aggregateVersion(1L)
                            .updatedAt(Instant.EPOCH)
                            .build()));

            PeopleAvailabilityResponse response = service.fetchAvailability(SITE_ID.toString(), TODAY);

            assertThat(response.getPeople())
                    .extracting(PeopleAvailabilityResponse.PersonAvailability::getPersonId)
                    .containsExactly(KEPT_ID.toString());
        }

        @Test
        @DisplayName("keeps an ON_LEAVE person")
        void onLeaveKept() {
            when(assignmentReplicaRepository.findByLocationIdAndStatus(SITE_ID, ACTIVE))
                    .thenReturn(List.of(assignmentFor(PERSON_ID)));
            when(employeeReplicaRepository.findByPersonIdIn(any()))
                    .thenReturn(List.of(employee("ON_LEAVE", null, Instant.EPOCH)));
            when(personReplicaRepository.findByPersonIdIn(any())).thenReturn(List.of());

            assertThat(service.fetchAvailability(SITE_ID.toString(), TODAY).getPeople())
                    .hasSize(1);
        }

        @Test
        @DisplayName("an empty roster does not query the employee replica")
        void emptyRoster() {
            when(assignmentReplicaRepository.findByLocationIdAndStatus(SITE_ID, ACTIVE))
                    .thenReturn(List.of());
            when(personReplicaRepository.findByPersonIdIn(any())).thenReturn(List.of());

            assertThat(service.fetchAvailability(SITE_ID.toString(), TODAY).getPeople())
                    .isEmpty();
            org.mockito.Mockito.verifyNoInteractions(employeeReplicaRepository);
        }
    }

    @Nested
    @DisplayName("#2122: certifications from the credential replica")
    class Certifications {

        private void rosterOfOne() {
            ExtStaffingAssignmentReplica a = row(SITE_ID, true, null, null);
            when(assignmentReplicaRepository.findByLocationIdAndStatus(SITE_ID, ACTIVE))
                    .thenReturn(List.of(a));
            when(personReplicaRepository.findByPersonIdIn(any())).thenReturn(List.of());
        }

        private ExtPersonCredentialReplica credential(
                String skillCode, String competenceCode, String status, LocalDate expiresOn) {
            return credential(skillCode, competenceCode, status, LocalDate.parse("2024-01-01"), expiresOn);
        }

        private ExtPersonCredentialReplica credential(
                String skillCode, String competenceCode, String status, LocalDate issuedOn, LocalDate expiresOn) {
            return ExtPersonCredentialReplica.builder()
                    .credentialId(UUID.randomUUID())
                    .personId(PERSON_ID)
                    .skillId(UUID.randomUUID())
                    .skillCode(skillCode)
                    .competenceCode(competenceCode)
                    .issuedOn(issuedOn)
                    .expiresOn(expiresOn)
                    .status(status)
                    .aggregateVersion(1L)
                    .updatedAt(Instant.EPOCH)
                    .build();
        }

        private List<String> certificationsOfOnlyPerson() {
            return service.fetchAvailability(SITE_ID.toString(), TODAY)
                    .getPeople()
                    .getFirst()
                    .getCertifications();
        }

        @Test
        @DisplayName("no credential rows at all leaves certifications null (no data)")
        void noRowsIsNull() {
            rosterOfOne();

            assertThat(certificationsOfOnlyPerson()).isNull();
        }

        @Test
        @DisplayName("lists skill and competence codes of ACTIVE, unexpired credentials")
        void activeUnexpiredHeld() {
            rosterOfOne();
            when(credentialReplicaRepository.findByPersonIdIn(any()))
                    .thenReturn(List.of(
                            credential("BRAKES-LIGHT", "BRAKES", ACTIVE, null),
                            credential("HVAC-LIGHT", "HVAC", ACTIVE, TODAY)));

            assertThat(certificationsOfOnlyPerson())
                    .containsExactlyInAnyOrder("BRAKES-LIGHT", "BRAKES", "HVAC-LIGHT", "HVAC");
        }

        @Test
        @DisplayName("expired, revoked and superseded credentials are not held; the list is empty, not null")
        void notHeldIsEmptyNotNull() {
            rosterOfOne();
            when(credentialReplicaRepository.findByPersonIdIn(any()))
                    .thenReturn(List.of(
                            credential("BRAKES-LIGHT", "BRAKES", ACTIVE, TODAY.minusDays(1)),
                            credential("HVAC-LIGHT", "HVAC", "REVOKED", null),
                            credential("A5-BRAKES", "BRAKES", "SUPERSEDED", null)));

            assertThat(certificationsOfOnlyPerson()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("the feed's EXPIRED is not trusted: a credential expiring on the date asked about is still held")
        void feedExpiredOnExpiryDayIsHeld() {
            // pos-people stamps status from its own clock, so EXPIRED arrives once its UTC day has
            // passed expiresOn even though the expiry day still counts for the facility.
            rosterOfOne();
            when(credentialReplicaRepository.findByPersonIdIn(any()))
                    .thenReturn(List.of(credential("BRAKES-LIGHT", "BRAKES", "EXPIRED", TODAY)));

            assertThat(certificationsOfOnlyPerson()).containsExactlyInAnyOrder("BRAKES-LIGHT", "BRAKES");
        }

        @Test
        @DisplayName("a credential issued after the date asked about is not held yet")
        void issuedAfterDateIsNotHeld() {
            rosterOfOne();
            when(credentialReplicaRepository.findByPersonIdIn(any()))
                    .thenReturn(List.of(credential("BRAKES-LIGHT", "BRAKES", ACTIVE, TODAY.plusDays(1), null)));

            assertThat(certificationsOfOnlyPerson()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("REVOKED stands as received even when the dates would say held")
        void revokedWithValidDatesIsNotHeld() {
            rosterOfOne();
            when(credentialReplicaRepository.findByPersonIdIn(any()))
                    .thenReturn(List.of(
                            credential("BRAKES-LIGHT", "BRAKES", "REVOKED", TODAY.minusYears(1), TODAY.plusYears(1))));

            assertThat(certificationsOfOnlyPerson()).isNotNull().isEmpty();
        }
    }
}
