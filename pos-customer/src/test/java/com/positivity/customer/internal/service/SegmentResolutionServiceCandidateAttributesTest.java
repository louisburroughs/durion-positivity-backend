package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.domain.PartyAttributes;
import com.positivity.customer.internal.domain.SegmentPredicate;
import com.positivity.customer.internal.entity.BillingRulesEmbeddable;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.CommunicationPreference;
import com.positivity.customer.internal.entity.ExtOrganizationPostalAddress;
import com.positivity.customer.internal.entity.ExtPersonReplica;
import com.positivity.customer.internal.entity.ExtVehicle;
import com.positivity.customer.internal.entity.ExtVehicleCarePreference;
import com.positivity.customer.internal.entity.PartyTagAssignment;
import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.entity.Segment;
import com.positivity.customer.internal.enums.AccountStatus;
import com.positivity.customer.internal.enums.AccountTier;
import com.positivity.customer.internal.enums.AudienceType;
import com.positivity.customer.internal.enums.MarketingConsent;
import com.positivity.customer.internal.enums.PartyType;
import com.positivity.customer.internal.enums.SegmentOperator;
import com.positivity.customer.internal.enums.SegmentType;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.CommunicationPreferenceRepository;
import com.positivity.customer.internal.repository.ExtOrganizationPostalAddressRepository;
import com.positivity.customer.internal.repository.ExtPersonReplicaRepository;
import com.positivity.customer.internal.repository.ExtVehicleCarePreferenceRepository;
import com.positivity.customer.internal.repository.ExtVehicleRepository;
import com.positivity.customer.internal.repository.FollowUpTaskRepository;
import com.positivity.customer.internal.repository.FollowUpTaskRepository.PartyLastDecline;
import com.positivity.customer.internal.repository.PartyTagAssignmentRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.customer.internal.repository.SegmentMemberRepository;
import com.positivity.customer.internal.repository.ServiceHistoryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The candidate attribute snapshot: what each party projects into {@link PartyAttributes}, and what
 * it projects when the underlying field is absent.
 *
 * <h2>Why this test exists</h2>
 *
 * The commercial mapper had no branch coverage at all — 0 of its 26 branches — despite being
 * thirteen consecutive null guards. Every one of them is a targeting decision: the snapshot is what
 * a segment predicate is evaluated against, so a guard that fell the wrong way would not fail
 * anything loudly, it would quietly put the wrong customers into a campaign (or leave the right
 * ones out). These tests pin both arms of each guard before the method is split up, so the split is
 * demonstrably behaviour-preserving rather than merely believed to be.
 */
@DisplayName("SegmentResolutionService — candidate attribute projection")
class SegmentResolutionServiceCandidateAttributesTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-20T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID PARTY_ID = UUID.fromString("01980a58-0001-7000-8000-000000000001");
    private static final UUID PERSON_ID = UUID.fromString("01980a58-0001-7000-8000-000000000002");
    private static final UUID TAG_A = UUID.fromString("01980a58-0001-7000-8000-00000000000a");
    private static final UUID TAG_B = UUID.fromString("01980a58-0001-7000-8000-00000000000b");

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
        when(commercialParties.findAll()).thenReturn(List.of());
        when(personParties.findAll()).thenReturn(List.of());
        when(preferences.findByPartyIdIn(any())).thenReturn(List.of());
        when(tags.findByPartyIdIn(any())).thenReturn(List.of());
        when(vehicles.findByAccountIdIn(anyCollection())).thenReturn(List.of());
        when(serviceHistory.findLastServiceByPartyAndVehicle(any())).thenReturn(List.of());
        when(followUps.findLastDeclinedByParty(any())).thenReturn(List.of());
        when(personReplicas.findAllById(any())).thenReturn(List.of());
        when(orgAddresses.findAllById(any())).thenReturn(List.of());
        when(carePreferences.findAllById(any())).thenReturn(List.of());
    }

    @Nested
    @DisplayName("commercial accounts")
    class Commercial {

        @Test
        @DisplayName("a fully populated account projects every attribute from its own record")
        void fullyPopulatedAccountProjectsEveryAttribute() {
            CommercialParty account = new CommercialParty();
            account.setPartyId(PARTY_ID);
            account.setPartyType(PartyType.COMMERCIAL);
            account.setTier(AccountTier.PLATINUM);
            account.setStatus(AccountStatus.ACTIVE);
            account.setParentParty(new CommercialParty());
            account.setExternalIdentifiers(Map.of("erp", "ACME-1"));
            account.setDisplayName("Acme Fleet");
            account.setLegalName("Acme Holdings GmbH");
            BillingRulesEmbeddable billing = new BillingRulesEmbeddable();
            billing.setTaxExempt(true);
            billing.setCreditHold(false);
            billing.setPaymentTerms("NET30");
            account.setBillingRules(billing);
            when(commercialParties.findAll()).thenReturn(List.of(account));
            when(orgAddresses.findAllById(any())).thenReturn(List.of(address()));
            when(preferences.findByPartyIdIn(any()))
                    .thenReturn(List.of(preference(MarketingConsent.OPT_IN, MarketingConsent.OPT_OUT)));
            when(tags.findByPartyIdIn(any())).thenReturn(List.of(tagAssignment(TAG_A), tagAssignment(TAG_B)));
            when(vehicles.findByAccountIdIn(anyCollection()))
                    .thenReturn(List.of(vehicle("Ford", "Transit", 2021, true)));

            PartyAttributes party = only(service.loadCandidates(AudienceType.COMMERCIAL));

            assertThat(party.partyId()).isEqualTo(PARTY_ID);
            assertThat(party.partyType()).isEqualTo("COMMERCIAL");
            assertThat(party.accountTier()).isEqualTo("PLATINUM");
            assertThat(party.accountStatus()).isEqualTo("ACTIVE");
            assertThat(party.hasParentParty()).isTrue();
            assertThat(party.externalIdentifiers()).containsEntry("erp", "ACME-1");
            // Two assignments for one party accumulate rather than the second replacing the first.
            assertThat(party.tagIds()).containsExactlyInAnyOrder(TAG_A, TAG_B);
            assertThat(party.taxExempt()).isTrue();
            assertThat(party.creditHold()).isFalse();
            assertThat(party.paymentTerms()).isEqualTo("NET30");
            assertThat(party.marketingEmailConsent()).isEqualTo("OPT_IN");
            assertThat(party.marketingSmsConsent()).isEqualTo("OPT_OUT");
            assertThat(party.vehicleMakes()).containsExactly("Ford");
            assertThat(party.vehicleModels()).containsExactly("Transit");
            assertThat(party.vehicleYears()).containsExactly(2021);
            assertThat(party.hasActiveVehicle()).isTrue();
            assertThat(party.vehicleCount()).isEqualTo(1);
            // The display name wins over the legal name when one is set.
            assertThat(party.displayLabel()).isEqualTo("Acme Fleet");
            assertThat(party.addressCountry()).isEqualTo("DE");
            assertThat(party.addressRegion()).isEqualTo("Bayern");
            assertThat(party.addressCity()).isEqualTo("München");
            assertThat(party.addressPostalCode()).isEqualTo("80331");
        }

        @Test
        @DisplayName("an account with nothing on file projects the documented fallbacks, not nulls throughout")
        void accountWithNothingOnFileProjectsTheFallbacks() {
            CommercialParty account = new CommercialParty();
            account.setPartyId(PARTY_ID);
            account.setPartyType(null);
            account.setTier(null);
            account.setStatus(null);
            account.setParentParty(null);
            account.setExternalIdentifiers(null);
            account.setDisplayName(null);
            account.setLegalName("Acme Holdings GmbH");
            account.setBillingRules(null);
            when(commercialParties.findAll()).thenReturn(List.of(account));

            PartyAttributes party = only(service.loadCandidates(AudienceType.COMMERCIAL));

            // partyType falls back to the literal rather than null: a predicate on party.type is a
            // string comparison, and a null there would silently match nothing at all.
            assertThat(party.partyType()).isEqualTo("COMMERCIAL");
            assertThat(party.accountTier()).isNull();
            assertThat(party.accountStatus()).isNull();
            assertThat(party.hasParentParty()).isFalse();
            assertThat(party.externalIdentifiers()).isEmpty();
            assertThat(party.tagIds()).isEmpty();
            assertThat(party.taxExempt()).isNull();
            assertThat(party.creditHold()).isNull();
            assertThat(party.paymentTerms()).isNull();
            // No preference row at all is UNSET, not an implied opt-in.
            assertThat(party.marketingEmailConsent()).isEqualTo("UNSET");
            assertThat(party.marketingSmsConsent()).isEqualTo("UNSET");
            assertThat(party.vehicleMakes()).isEmpty();
            assertThat(party.vehicleModels()).isEmpty();
            assertThat(party.vehicleYears()).isEmpty();
            assertThat(party.hasActiveVehicle()).isFalse();
            assertThat(party.vehicleCount()).isZero();
            // The legal name is the fallback label; an unnamed account would be unidentifiable.
            assertThat(party.displayLabel()).isEqualTo("Acme Holdings GmbH");
            assertThat(party.addressCountry()).isNull();
            assertThat(party.addressRegion()).isNull();
            assertThat(party.addressCity()).isNull();
            assertThat(party.addressPostalCode()).isNull();
            assertThat(party.monthsSinceLastService()).isNull();
            assertThat(party.hasServiceHistory()).isFalse();
            assertThat(party.daysSinceDeclinedService()).isNull();
            assertThat(party.serviceDue()).isFalse();
        }

        @Test
        @DisplayName("a stored preference row with null consents still reads UNSET")
        void storedPreferenceWithNullConsentsReadsUnset() {
            when(commercialParties.findAll()).thenReturn(List.of(minimalAccount()));
            when(preferences.findByPartyIdIn(any())).thenReturn(List.of(preference(null, null)));

            PartyAttributes party = only(service.loadCandidates(AudienceType.COMMERCIAL));

            // Distinct from "no row": the party has a preference record whose marketing consents
            // were never answered. Both collapse to UNSET so consent is never inferred from silence.
            assertThat(party.marketingEmailConsent()).isEqualTo("UNSET");
            assertThat(party.marketingSmsConsent()).isEqualTo("UNSET");
        }

        @Test
        @DisplayName(
                "days since a declined recommendation counts from the stored instant, and null declines are ignored")
        void daysSinceDeclineCountsFromTheStoredInstant() {
            when(commercialParties.findAll()).thenReturn(List.of(minimalAccount()));
            when(followUps.findLastDeclinedByParty(any()))
                    .thenReturn(List.of(
                            lastDecline(PARTY_ID, Instant.parse("2026-07-10T08:00:00Z")),
                            // A party row carrying no decline instant must not land in the map as a
                            // null, which would read as "declined today" rather than "never".
                            lastDecline(UUID.fromString("01980a58-0001-7000-8000-0000000000ff"), null)));

            PartyAttributes party = only(service.loadCandidates(AudienceType.COMMERCIAL));

            assertThat(party.daysSinceDeclinedService()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("individual parties")
    class Individual {

        @Test
        @DisplayName("a person projects the replica address and the customer number as its label")
        void personProjectsReplicaAddress() {
            PersonParty person = new PersonParty();
            person.setPartyId(PARTY_ID);
            person.setPersonId(PERSON_ID);
            person.setTier(AccountTier.GOLD);
            person.setStatus(AccountStatus.ON_HOLD);
            person.setCustomerNumber("C-0001");
            ExtPersonReplica replica = new ExtPersonReplica();
            replica.setPersonId(PERSON_ID);
            replica.setAddressCountryCode("US");
            replica.setAddressRegion("TX");
            replica.setAddressCity("Austin");
            replica.setAddressPostalCode("73301");
            when(personParties.findAll()).thenReturn(List.of(person));
            when(personReplicas.findAllById(any())).thenReturn(List.of(replica));

            PartyAttributes party = only(service.loadCandidates(AudienceType.INDIVIDUAL));

            assertThat(party.partyType()).isEqualTo("PERSON");
            assertThat(party.accountTier()).isEqualTo("GOLD");
            assertThat(party.accountStatus()).isEqualTo("ON_HOLD");
            // Person names live in pos-people-contact (ADR-0015), so the customer number is the label.
            assertThat(party.displayLabel()).isEqualTo("C-0001");
            assertThat(party.addressCountry()).isEqualTo("US");
            assertThat(party.addressRegion()).isEqualTo("TX");
            assertThat(party.addressCity()).isEqualTo("Austin");
            assertThat(party.addressPostalCode()).isEqualTo("73301");
            // Commercial-only attributes are absent rather than defaulted for a person.
            assertThat(party.hasParentParty()).isFalse();
            assertThat(party.externalIdentifiers()).isEmpty();
            assertThat(party.taxExempt()).isNull();
            assertThat(party.creditHold()).isNull();
            assertThat(party.paymentTerms()).isNull();
        }

        @Test
        @DisplayName("a person whose replica has not arrived yet projects a null address, not a failure")
        void personWithoutAReplicaRowProjectsNoAddress() {
            PersonParty person = new PersonParty();
            person.setPartyId(PARTY_ID);
            person.setPersonId(PERSON_ID);
            person.setCustomerNumber("C-0002");
            when(personParties.findAll()).thenReturn(List.of(person));
            // The person exists locally but ext_person_replica has nothing for them yet --
            // ordinary replication lag, not an error. The party must still be a resolvable
            // candidate, just one that no address predicate can match.
            when(personReplicas.findAllById(any())).thenReturn(List.of());

            PartyAttributes party = only(service.loadCandidates(AudienceType.INDIVIDUAL));

            assertThat(party.partyId()).isEqualTo(PARTY_ID);
            assertThat(party.displayLabel()).isEqualTo("C-0002");
            assertThat(party.addressCountry()).isNull();
            assertThat(party.addressRegion()).isNull();
            assertThat(party.addressCity()).isNull();
            assertThat(party.addressPostalCode()).isNull();
        }
    }

    @Nested
    @DisplayName("service-due inputs and the candidate ceiling")
    class ServiceAndCeiling {

        private static final UUID VEHICLE_ID = UUID.fromString("01980a58-0001-7000-8000-0000000000c1");

        @Test
        @DisplayName("a vehicle scope with no completion instant is dropped rather than counted as never-due")
        void scopesWithoutACompletionAreDropped() {
            when(commercialParties.findAll()).thenReturn(List.of(minimalAccount()));
            when(serviceHistory.findLastServiceByPartyAndVehicle(any()))
                    .thenReturn(List.of(
                            lastService(VEHICLE_ID, null),
                            lastService(VEHICLE_ID, Instant.parse("2025-07-20T12:00:00Z"))));

            PartyAttributes party = only(service.loadCandidates(AudienceType.COMMERCIAL));

            // The null-completion row must not participate: keeping it would let the party-level
            // "most recent completion" be computed against a scope that has never been serviced.
            assertThat(party.hasServiceHistory()).isTrue();
            assertThat(party.monthsSinceLastService()).isEqualTo(12);
            assertThat(party.serviceDue()).isTrue();
        }

        @Test
        @DisplayName("a non-positive replicated service interval is ignored in favour of the default")
        void nonPositiveIntervalOverrideFallsBackToTheDefault() {
            when(commercialParties.findAll()).thenReturn(List.of(minimalAccount()));
            when(serviceHistory.findLastServiceByPartyAndVehicle(any()))
                    .thenReturn(List.of(lastService(VEHICLE_ID, Instant.parse("2026-06-20T12:00:00Z"))));
            when(carePreferences.findAllById(any())).thenReturn(List.of(carePreference(0)));

            PartyAttributes party = only(service.loadCandidates(AudienceType.COMMERCIAL));

            // An interval of zero can only be corrupt replica data. Honouring it would make every
            // vehicle permanently service-due, which is a reminder sent to every customer forever.
            // Falling back to the six-month default leaves this one-month-old service not due.
            assertThat(party.serviceDue()).isFalse();
        }

        @Test
        @DisplayName("a replicated interval of null is ignored in favour of the default")
        void absentIntervalOverrideFallsBackToTheDefault() {
            when(commercialParties.findAll()).thenReturn(List.of(minimalAccount()));
            when(serviceHistory.findLastServiceByPartyAndVehicle(any()))
                    .thenReturn(List.of(lastService(VEHICLE_ID, Instant.parse("2026-06-20T12:00:00Z"))));
            // A replica row exists for the vehicle but carries no interval -- a tombstoned or
            // never-populated care preference, which is not the same as an interval of zero.
            when(carePreferences.findAllById(any())).thenReturn(List.of(carePreference(null)));

            assertThat(only(service.loadCandidates(AudienceType.COMMERCIAL)).serviceDue())
                    .isFalse();
        }

        @Test
        @DisplayName("a positive replicated interval overrides the default")
        void positiveIntervalOverrideWins() {
            when(commercialParties.findAll()).thenReturn(List.of(minimalAccount()));
            when(serviceHistory.findLastServiceByPartyAndVehicle(any()))
                    .thenReturn(List.of(lastService(VEHICLE_ID, Instant.parse("2026-06-20T12:00:00Z"))));
            when(carePreferences.findAllById(any())).thenReturn(List.of(carePreference(1)));

            PartyAttributes party = only(service.loadCandidates(AudienceType.COMMERCIAL));

            assertThat(party.serviceDue()).isTrue();
        }

        @Test
        @DisplayName("a resolution that hits the candidate ceiling reports itself truncated")
        void resolutionAtTheCeilingIsFlaggedTruncated() {
            List<CommercialParty> ceiling = java.util.stream.IntStream.range(0, SegmentResolutionService.MAX_CANDIDATES)
                    .mapToObj(index -> {
                        CommercialParty account = new CommercialParty();
                        account.setPartyId(UUID.randomUUID());
                        account.setTier(AccountTier.GOLD);
                        return account;
                    })
                    .toList();
            when(commercialParties.findAll()).thenReturn(ceiling);

            SegmentResolutionService.Resolution resolution = service.resolve(
                    Segment.builder()
                            .segmentId(UUID.fromString("01980a58-0001-7000-8000-0000000000e1"))
                            .name("Everyone gold")
                            .audienceType(AudienceType.COMMERCIAL)
                            .type(SegmentType.DYNAMIC)
                            .active(true)
                            .build(),
                    Optional.of(
                            new SegmentPredicate.Comparison("party.accountTier", SegmentOperator.IN, List.of("GOLD"))));

            // The flag is the only signal an operator gets that their campaign did not reach
            // everyone it should have; a silent short result reads as a correct small audience.
            assertThat(resolution.truncated()).isTrue();
            assertThat(resolution.partyIds()).hasSize(SegmentResolutionService.MAX_CANDIDATES);
        }

        private ExtVehicleCarePreference carePreference(Integer intervalMonths) {
            ExtVehicleCarePreference preference = new ExtVehicleCarePreference();
            preference.setVehicleId(VEHICLE_ID);
            preference.setServiceIntervalMonths(intervalMonths);
            return preference;
        }

        private ServiceHistoryRepository.PartyVehicleLastServiceView lastService(UUID vehicleId, Instant completedAt) {
            return new ServiceHistoryRepository.PartyVehicleLastServiceView() {
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
    }

    private static PartyAttributes only(List<PartyAttributes> candidates) {
        assertThat(candidates).hasSize(1);
        return candidates.getFirst();
    }

    private static CommercialParty minimalAccount() {
        CommercialParty account = new CommercialParty();
        account.setPartyId(PARTY_ID);
        account.setLegalName("Acme Holdings GmbH");
        return account;
    }

    private static ExtOrganizationPostalAddress address() {
        ExtOrganizationPostalAddress address = new ExtOrganizationPostalAddress();
        address.setOrganizationId(PARTY_ID);
        address.setCountryCode("DE");
        address.setRegion("Bayern");
        address.setCity("München");
        address.setPostalCode("80331");
        return address;
    }

    private static CommunicationPreference preference(MarketingConsent email, MarketingConsent sms) {
        CommunicationPreference preference = new CommunicationPreference();
        preference.setPartyId(PARTY_ID);
        preference.setMarketingEmailConsent(email);
        preference.setMarketingSmsConsent(sms);
        return preference;
    }

    private static PartyTagAssignment tagAssignment(UUID tagId) {
        PartyTagAssignment assignment = new PartyTagAssignment();
        assignment.setPartyId(PARTY_ID);
        assignment.setTagId(tagId);
        return assignment;
    }

    private static ExtVehicle vehicle(String make, String model, Integer year, boolean active) {
        ExtVehicle vehicle = new ExtVehicle();
        vehicle.setVehicleId(UUID.fromString("01980a58-0001-7000-8000-0000000000c1"));
        vehicle.setAccountId(PARTY_ID);
        vehicle.setMake(make);
        vehicle.setModel(model);
        vehicle.setYear(year);
        vehicle.setActive(active);
        return vehicle;
    }

    private static PartyLastDecline lastDecline(UUID partyId, Instant declinedAt) {
        return new PartyLastDecline() {
            @Override
            public UUID getPartyId() {
                return partyId;
            }

            @Override
            public Instant getLastDeclinedAt() {
                return declinedAt;
            }
        };
    }
}
