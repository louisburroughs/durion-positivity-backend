package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.positivity.shopmanager.internal.entity.ExtBayReplica;
import com.positivity.shopmanager.internal.entity.ExtCatalogServiceReplica;
import com.positivity.shopmanager.internal.repository.ExtBaySpecialtyMapReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtCatalogServiceReplicaRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * DECISION-SHOPMGMT-021: the one bay-eligibility rule, shared by the opening search and
 * appointment submit/reschedule. These tests cover the shared function directly, independent of
 * either caller.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BayEligibilityService (DECISION-SHOPMGMT-021)")
class BayEligibilityServiceTest {

    private static final UUID BAY_1 = UUID.fromString("01960005-0000-7000-8000-0000000000c1");
    private static final UUID RACK = UUID.fromString("01960005-0000-7000-8000-0000000000c2");
    private static final UUID WASH = UUID.fromString("01960005-0000-7000-8000-0000000000c3");
    private static final UUID SERVICE_1 = UUID.fromString("0196cf6f-c8dd-7ee0-93e7-f48a5698a601");

    @Mock
    private ExtBaySpecialtyMapReplicaRepository specialtyMapRepository;

    @Mock
    private ExtCatalogServiceReplicaRepository catalogServiceRepository;

    private BayEligibilityService service;

    @BeforeEach
    void setUp() {
        service = new BayEligibilityService(specialtyMapRepository, catalogServiceRepository);
    }

    private static ExtBayReplica bay(UUID id, List<String> codes, boolean acceptsGeneralWork, Integer maxDutyClass) {
        return ExtBayReplica.builder()
                .bayId(id)
                .active(true)
                .serviceCapabilityCodes(codes)
                .acceptsGeneralWork(acceptsGeneralWork)
                .maxDutyClass(maxDutyClass)
                .build();
    }

    /** A booking with no service whose operation code could not be resolved (the common case here). */
    private static BayEligibilityService.BookedOperations operations(Set<String> codes) {
        return new BayEligibilityService.BookedOperations(codes, false);
    }

    @Test
    @DisplayName("rule 4: with the map present, a specialty operation is unbookable in a bay that does not claim it,"
            + " even though a general bay is active")
    void specialtyMapPresentRefusesGeneralBaysForAMapNamedOperation() {
        when(specialtyMapRepository.count()).thenReturn(1L);
        when(specialtyMapRepository.existsByOperationCode("WHEEL-ALIGNMENT-4-WHEEL"))
                .thenReturn(true);
        List<ExtBayReplica> bays = List.of(bay(BAY_1, List.of(), true, null));

        BayEligibilityService.Eligibility eligibility =
                service.eligibleBays(bays, operations(Set.of("WHEEL-ALIGNMENT-4-WHEEL")), null);

        assertThat(eligibility.eligible()).isEmpty();
        assertThat(eligibility.byCapability()).isEqualTo(1);
        assertThat(eligibility.refusalByBay()).containsEntry(BAY_1, BayEligibilityService.Refusal.NOT_EQUIPPED);
    }

    @Test
    @DisplayName("rule 4: a bay claiming the specialty operation is eligible for it")
    void claimingBayIsEligibleForItsSpecialty() {
        when(specialtyMapRepository.count()).thenReturn(1L);
        when(specialtyMapRepository.existsByOperationCode("WHEEL-ALIGNMENT-4-WHEEL"))
                .thenReturn(true);
        ExtBayReplica rack = bay(RACK, List.of("WHEEL-ALIGNMENT-4-WHEEL"), true, null);

        Optional<BayEligibilityService.Refusal> refusal =
                service.refusalFor(rack, List.of(rack), operations(Set.of("WHEEL-ALIGNMENT-4-WHEEL")), null);

        assertThat(refusal).isEmpty();
    }

    @Test
    @DisplayName("an operation the map does not name is general work, open to any bay accepting general work")
    void unlistedOperationIsGeneralWork() {
        when(specialtyMapRepository.count()).thenReturn(1L);
        when(specialtyMapRepository.existsByOperationCode("OIL-CHANGE-FULL-SYNTHETIC"))
                .thenReturn(false);
        ExtBayReplica general = bay(BAY_1, List.of(), true, null);

        Optional<BayEligibilityService.Refusal> refusal =
                service.refusalFor(general, List.of(general), operations(Set.of("OIL-CHANGE-FULL-SYNTHETIC")), null);

        assertThat(refusal).isEmpty();
    }

    @Test
    @DisplayName("D14: a WASH_DETAIL bay (accepts_general_work=false) never takes general work")
    void washBayNeverTakesGeneralWork() {
        when(specialtyMapRepository.count()).thenReturn(1L);
        when(specialtyMapRepository.existsByOperationCode("OIL-CHANGE-FULL-SYNTHETIC"))
                .thenReturn(false);
        ExtBayReplica wash = bay(WASH, List.of(), false, null);

        Optional<BayEligibilityService.Refusal> refusal =
                service.refusalFor(wash, List.of(wash), operations(Set.of("OIL-CHANGE-FULL-SYNTHETIC")), null);

        assertThat(refusal).contains(BayEligibilityService.Refusal.NOT_EQUIPPED);
    }

    @Test
    @DisplayName("duty class: a class above maxDutyClass is excluded; an unknown class (either side null) passes")
    void dutyClassCapsButSkipsWhenEitherSideUnknown() {
        ExtBayReplica ceilingThree = bay(BAY_1, List.of(), true, 3);

        assertThat(service.refusalFor(ceilingThree, List.of(ceilingThree), operations(Set.of()), 7))
                .contains(BayEligibilityService.Refusal.DUTY_CLASS_EXCEEDED);
        assertThat(service.refusalFor(ceilingThree, List.of(ceilingThree), operations(Set.of()), null))
                .isEmpty();
        ExtBayReplica unconstrained = bay(RACK, List.of(), true, null);
        assertThat(service.refusalFor(unconstrained, List.of(unconstrained), operations(Set.of()), 7))
                .isEmpty();
    }

    @Test
    @DisplayName("empty specialty map (not yet arrived): specialty is derived from which bay claims the operation,"
            + " same as before this story")
    void emptyMapFallsBackToClaimDerivedSpecialty() {
        when(specialtyMapRepository.count()).thenReturn(0L);
        ExtBayReplica general = bay(BAY_1, List.of(), true, null);
        ExtBayReplica rack = bay(RACK, List.of("WHEEL-ALIGNMENT-4-WHEEL"), true, null);
        List<ExtBayReplica> bays = List.of(general, rack);

        Set<String> specialty = service.specialtyOperations(Set.of("WHEEL-ALIGNMENT-4-WHEEL"), bays);

        assertThat(specialty).containsExactly("WHEEL-ALIGNMENT-4-WHEEL");
        assertThat(service.refusalFor(general, bays, operations(Set.of("WHEEL-ALIGNMENT-4-WHEEL")), null))
                .contains(BayEligibilityService.Refusal.NOT_EQUIPPED);
        assertThat(service.refusalFor(rack, bays, operations(Set.of("WHEEL-ALIGNMENT-4-WHEEL")), null))
                .isEmpty();
    }

    @Test
    @DisplayName("empty specialty map: an operation nobody claims reads as general, not specialty")
    void emptyMapTreatsUnclaimedOperationAsGeneral() {
        when(specialtyMapRepository.count()).thenReturn(0L);
        ExtBayReplica general = bay(BAY_1, List.of(), true, null);

        Set<String> specialty = service.specialtyOperations(Set.of("OIL-CHANGE-FULL-SYNTHETIC"), List.of(general));

        assertThat(specialty).isEmpty();
    }

    @Test
    @DisplayName("eligibleBays reports capability and duty-class misses separately")
    void eligibleBaysCountsMissesSeparately() {
        when(specialtyMapRepository.count()).thenReturn(0L);
        ExtBayReplica general = bay(BAY_1, List.of(), true, 3);
        ExtBayReplica wash = bay(WASH, List.of(), false, null);
        List<ExtBayReplica> bays = List.of(general, wash);

        BayEligibilityService.Eligibility eligibility =
                service.eligibleBays(bays, operations(Set.of("OIL-CHANGE-FULL-SYNTHETIC")), 7);

        assertThat(eligibility.active()).isEqualTo(2);
        assertThat(eligibility.eligible()).isEmpty();
        // Bay 1 fails on duty class (7 > 3); the wash bay fails on capability (no general work).
        assertThat(eligibility.byDutyClass()).isEqualTo(1);
        assertThat(eligibility.byCapability()).isEqualTo(1);
    }

    @Test
    @DisplayName("operationCodesOf resolves, normalizes and drops blank operation codes")
    void operationCodesOfNormalizesAndFiltersBlanks() {
        lenient()
                .when(catalogServiceRepository.findAllByServiceIdIn(any()))
                .thenReturn(List.of(
                        ExtCatalogServiceReplica.builder()
                                .serviceId(SERVICE_1)
                                .operationCode(" wheel-alignment-4-wheel ")
                                .active(true)
                                .build(),
                        ExtCatalogServiceReplica.builder()
                                .serviceId(UUID.randomUUID())
                                .operationCode(null)
                                .active(true)
                                .build()));

        // The second requested id matches neither returned row, so it is unresolved too.
        BayEligibilityService.BookedOperations booked = service.operationCodesOf(List.of(SERVICE_1, UUID.randomUUID()));

        assertThat(booked.codes()).containsExactly("WHEEL-ALIGNMENT-4-WHEEL");
        assertThat(booked.hasUnresolvedOperation()).isTrue();
        assertThat(service.operationCodesOf(List.of(SERVICE_1)).hasUnresolvedOperation())
                .isFalse();
        assertThat(service.operationCodesOf(null)).isEqualTo(BayEligibilityService.BookedOperations.NONE);
        assertThat(service.operationCodesOf(List.of())).isEqualTo(BayEligibilityService.BookedOperations.NONE);
    }

    @Test
    @DisplayName("#2280 F6: a booked service with no resolvable operation code is still general work — a"
            + " WASH_DETAIL bay (accepts_general_work=false) refuses it, not passes it through invisibly")
    void unresolvedOperationCodeStillCountsAsGeneralWorkForAWashBay() {
        ExtBayReplica wash = bay(WASH, List.of(), false, null);
        BayEligibilityService.BookedOperations booked = new BayEligibilityService.BookedOperations(Set.of(), true);

        Optional<BayEligibilityService.Refusal> refusal = service.refusalFor(wash, List.of(wash), booked, null);

        assertThat(refusal).contains(BayEligibilityService.Refusal.NOT_EQUIPPED);
    }

    @Test
    @DisplayName("#2280 F6: the same unresolved-operation booking is eligible on a bay that accepts general work")
    void unresolvedOperationCodeIsEligibleOnAGeneralBay() {
        ExtBayReplica general = bay(BAY_1, List.of(), true, null);
        BayEligibilityService.BookedOperations booked = new BayEligibilityService.BookedOperations(Set.of(), true);

        Optional<BayEligibilityService.Refusal> refusal = service.refusalFor(general, List.of(general), booked, null);

        assertThat(refusal).isEmpty();
    }

    @Test
    @DisplayName("isGeneral (ranking only): a bay with no serviceCapabilityCodes is general")
    void isGeneralReflectsClaimedCodes() {
        assertThat(BayEligibilityService.isGeneral(bay(BAY_1, List.of(), true, null)))
                .isTrue();
        assertThat(BayEligibilityService.isGeneral(bay(RACK, List.of("WHEEL-ALIGNMENT-4-WHEEL"), true, null)))
                .isFalse();
        assertThat(BayEligibilityService.isGeneral(
                        ExtBayReplica.builder().bayId(BAY_1).active(true).build()))
                .isTrue();
    }

    @Test
    @DisplayName("specialtyOperations/eligibleBays return empty/no-op for an empty operation set")
    void emptyOperationSetIsTriviallyGeneral() {
        assertThat(service.specialtyOperations(Set.of(), List.of())).isEmpty();
        BayEligibilityService.Eligibility eligibility =
                service.eligibleBays(List.of(bay(BAY_1, List.of(), true, null)), operations(Set.of()), null);
        assertThat(eligibility.eligible()).hasSize(1);
    }
}
