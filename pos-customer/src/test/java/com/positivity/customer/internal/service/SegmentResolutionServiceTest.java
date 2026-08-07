package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.domain.PartyAttributes;
import com.positivity.customer.internal.entity.ExtVehicleCarePreference;
import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.enums.AudienceType;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.CommunicationPreferenceRepository;
import com.positivity.customer.internal.repository.ExtOrganizationPostalAddressRepository;
import com.positivity.customer.internal.repository.ExtPersonReplicaRepository;
import com.positivity.customer.internal.repository.ExtVehicleCarePreferenceRepository;
import com.positivity.customer.internal.repository.ExtVehicleRepository;
import com.positivity.customer.internal.repository.FollowUpTaskRepository;
import com.positivity.customer.internal.repository.PartyTagAssignmentRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.customer.internal.repository.SegmentMemberRepository;
import com.positivity.customer.internal.repository.ServiceHistoryRepository;
import com.positivity.customer.internal.repository.ServiceHistoryRepository.PartyVehicleLastServiceView;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SegmentResolutionService}'s effective per-vehicle service-due resolution
 * (#1144, #1175): the vehicle's replicated care-preference interval takes precedence over the
 * module-wide default, and vehicle-less history keeps using the default.
 */
class SegmentResolutionServiceTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-20T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID PARTY_ID = UUID.fromString("01980a58-0000-7000-8000-000000000001");
    private static final UUID VEHICLE_ID = UUID.fromString("01980a58-0000-7000-8000-000000000002");

    private final CommercialPartyRepository commercialParties = mock(CommercialPartyRepository.class);
    private final PersonPartyRepository personParties = mock(PersonPartyRepository.class);
    private final CommunicationPreferenceRepository preferences = mock(CommunicationPreferenceRepository.class);
    private final ExtVehicleRepository vehicles = mock(ExtVehicleRepository.class);
    private final PartyTagAssignmentRepository tags = mock(PartyTagAssignmentRepository.class);
    private final SegmentMemberRepository segmentMembers = mock(SegmentMemberRepository.class);
    private final ServiceHistoryRepository serviceHistory = mock(ServiceHistoryRepository.class);
    private final FollowUpTaskRepository followUps = mock(FollowUpTaskRepository.class);
    private final ExtPersonReplicaRepository personReplicas = mock(ExtPersonReplicaRepository.class);
    private final ExtOrganizationPostalAddressRepository orgAddresses =
            mock(ExtOrganizationPostalAddressRepository.class);
    private final ExtVehicleCarePreferenceRepository carePreferences = mock(ExtVehicleCarePreferenceRepository.class);

    private SegmentResolutionService service;

    @BeforeEach
    void setUp() {
        service = new SegmentResolutionService(
                commercialParties,
                personParties,
                preferences,
                vehicles,
                tags,
                segmentMembers,
                serviceHistory,
                followUps,
                personReplicas,
                orgAddresses,
                carePreferences,
                TEST_CLOCK);
        PersonParty person = new PersonParty();
        person.setPartyId(PARTY_ID);
        person.setPersonId(UUID.fromString("01980a58-0000-7000-8000-000000000009"));
        when(personParties.findAll()).thenReturn(List.of(person));
        when(personReplicas.findAllById(any())).thenReturn(List.of());
        when(preferences.findByPartyIdIn(any())).thenReturn(List.of());
        when(tags.findByPartyIdIn(any())).thenReturn(List.of());
        when(vehicles.findByAccountIdIn(anyCollection())).thenReturn(List.of());
        when(followUps.findLastDeclinedByParty(any())).thenReturn(List.of());
        when(carePreferences.findAllById(any())).thenReturn(List.of());
    }

    private static PartyVehicleLastServiceView lastService(UUID vehicleId, Instant completedAt) {
        return new PartyVehicleLastServiceView() {
            @Override
            public UUID getPartyId() {
                return PARTY_ID;
            }

            @Override
            public UUID getVehicleId() {
                return vehicleId;
            }

            @Override
            public Instant getLastCompletedAt() {
                return completedAt;
            }
        };
    }

    private void stubIntervalOverride(int months) {
        when(carePreferences.findAllById(any()))
                .thenReturn(List.of(ExtVehicleCarePreference.builder()
                        .vehicleId(VEHICLE_ID)
                        .serviceIntervalMonths(months)
                        .aggregateVersion(1000L)
                        .updatedAt(Instant.now(TEST_CLOCK))
                        .build()));
    }

    private PartyAttributes soleCandidate() {
        List<PartyAttributes> candidates = service.loadCandidates(AudienceType.INDIVIDUAL);
        assertThat(candidates).hasSize(1);
        return candidates.get(0);
    }

    @Test
    @DisplayName("A tighter per-vehicle interval makes the party service-due before the module default (#1175)")
    void tighterOverrideMakesDueEarlier() {
        // Four months old: not due at the 6-month default, due at the vehicle's 3-month interval.
        when(serviceHistory.findLastServiceByPartyAndVehicle(anyCollection()))
                .thenReturn(List.of(lastService(VEHICLE_ID, Instant.parse("2026-03-15T10:00:00Z"))));
        stubIntervalOverride(3);

        PartyAttributes party = soleCandidate();

        assertThat(party.serviceDue()).isTrue();
        assertThat(party.monthsSinceLastService()).isEqualTo(4);
    }

    @Test
    @DisplayName("A looser per-vehicle interval keeps the party not-due past the module default (#1175)")
    void looserOverrideDefersDue() {
        // Seven months old: due at the 6-month default, not due at the vehicle's 12-month interval.
        when(serviceHistory.findLastServiceByPartyAndVehicle(anyCollection()))
                .thenReturn(List.of(lastService(VEHICLE_ID, Instant.parse("2025-12-15T10:00:00Z"))));
        stubIntervalOverride(12);

        assertThat(soleCandidate().serviceDue()).isFalse();
    }

    @Test
    @DisplayName("Without an override the module default applies, and vehicle-less history uses it too")
    void defaultAppliesWithoutOverride() {
        when(serviceHistory.findLastServiceByPartyAndVehicle(anyCollection()))
                .thenReturn(List.of(lastService(null, Instant.parse("2025-12-15T10:00:00Z"))));

        assertThat(soleCandidate().serviceDue()).isTrue();
    }

    @Test
    @DisplayName("The party is due when any owned vehicle's effective interval has elapsed")
    void anyDueVehicleMakesPartyDue() {
        UUID otherVehicle = UUID.fromString("01980a58-0000-7000-8000-000000000003");
        when(serviceHistory.findLastServiceByPartyAndVehicle(anyCollection()))
                .thenReturn(List.of(
                        // 2 months old on the overridden 3-month vehicle: not due.
                        lastService(VEHICLE_ID, Instant.parse("2026-05-15T10:00:00Z")),
                        // 7 months old on a default-interval vehicle: due.
                        lastService(otherVehicle, Instant.parse("2025-12-15T10:00:00Z"))));
        stubIntervalOverride(3);

        PartyAttributes party = soleCandidate();

        assertThat(party.serviceDue()).isTrue();
        // monthsSinceLastService stays the max across scopes — the freshest completion.
        assertThat(party.monthsSinceLastService()).isEqualTo(2);
    }

    @Test
    @DisplayName("A party with no service history is never service-due")
    void noHistoryNeverDue() {
        when(serviceHistory.findLastServiceByPartyAndVehicle(anyCollection())).thenReturn(List.of());

        PartyAttributes party = soleCandidate();

        assertThat(party.serviceDue()).isFalse();
        assertThat(party.monthsSinceLastService()).isNull();
        assertThat(party.hasServiceHistory()).isFalse();
    }
}
