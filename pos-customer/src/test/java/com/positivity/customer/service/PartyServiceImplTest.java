package com.positivity.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.client.PeopleClient;
import com.positivity.customer.internal.client.VehicleInventoryClient;
import com.positivity.customer.internal.config.CacheConfig;
import com.positivity.customer.internal.dto.CreateCommercialAccountRequest;
import com.positivity.customer.internal.dto.CreateVehicleForPartyRequest;
import com.positivity.customer.internal.dto.GetCommunicationPreferencesResponse;
import com.positivity.customer.internal.dto.GetPartyResponse;
import com.positivity.customer.internal.dto.MergePartiesRequest;
import com.positivity.customer.internal.dto.MergePartiesResponse;
import com.positivity.customer.internal.dto.SearchPartiesRequest;
import com.positivity.customer.internal.dto.SearchPartiesResponse;
import com.positivity.customer.internal.dto.UpsertCommunicationPreferencesRequest;
import com.positivity.customer.internal.dto.UpsertCommunicationPreferencesResponse;
import com.positivity.customer.internal.dto.snapshot.CrmSnapshotDTO;
import com.positivity.customer.internal.dto.snapshot.SnapshotMetadata;
import com.positivity.customer.internal.entity.BillingRulesEmbeddable;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.PartyRelationship;
import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.enums.AccountStatus;
import com.positivity.customer.internal.enums.InvoiceDeliveryMethod;
import com.positivity.customer.internal.enums.PartyType;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.PartyRelationshipRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.customer.internal.service.PartyServiceImpl;
import com.positivity.shared.dto.VehicleResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class PartyServiceImplTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private CommercialPartyRepository partyRepository;

    @Mock
    private PersonPartyRepository personPartyRepository;

    @Mock
    private PartyRelationshipRepository partyRelationshipRepository;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache cache;

    @Mock
    private PeopleClient peopleClient;

    @Mock
    private VehicleInventoryClient vehicleInventoryClient;

    private PartyServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PartyServiceImpl(
                TEST_CLOCK,
                partyRepository,
                personPartyRepository,
                partyRelationshipRepository,
                cacheManager,
                peopleClient,
                vehicleInventoryClient);
    }

    private CommercialParty party(UUID id) {
        CommercialParty p = new CommercialParty();
        p.setPartyId(id);
        p.setCustomerNumber("CUST-001");
        p.setLegalName("Acme Corp");
        p.setDisplayName("Acme");
        p.setPartyType(PartyType.COMMERCIAL);
        p.setStatus(AccountStatus.ACTIVE);
        p.setCreatedAt(Instant.now(TEST_CLOCK));
        p.setModifiedAt(Instant.now(TEST_CLOCK));
        p.setVehicleVins(new HashSet<>());
        p.setExternalIdentifiers(new HashMap<>());
        return p;
    }

    private PersonParty personParty(UUID partyId, UUID personId) {
        PersonParty personParty = new PersonParty();
        personParty.setPartyId(partyId);
        personParty.setPersonId(personId);
        personParty.setCustomerNumber("CUST-P-001");
        personParty.setStatus(AccountStatus.ACTIVE);
        personParty.setCreatedAt(Instant.now(TEST_CLOCK));
        return personParty;
    }

    /** Build a canonical pos-people identity (sole source of name/contacts, ADR-0015 I2). */
    private static PeopleClient.PersonIdentity identity(
            UUID id, String first, String last, String email, String phone) {
        List<PeopleClient.ContactPoint> cps = new ArrayList<>();
        if (email != null) {
            cps.add(new PeopleClient.ContactPoint("EMAIL", email, true));
        }
        if (phone != null) {
            cps.add(new PeopleClient.ContactPoint("PHONE_MOBILE", phone, true));
        }
        return new PeopleClient.PersonIdentity(id, first, last, email, cps);
    }

    @Test
    void buildSnapshotForParty_returnsNull_whenPartyNotFound() {
        when(cacheManager.getCache(CacheConfig.SNAPSHOT_CACHE)).thenReturn(cache);
        when(cache.get(any())).thenReturn(null);
        when(partyRepository.findByPartyId(any())).thenReturn(null);

        CrmSnapshotDTO result = service.buildSnapshotForParty(UUID.fromString("00000000-0000-0000-0000-000000000001"));

        assertThat(result).isNull();
    }

    @Test
    void buildSnapshotForParty_returnsFreshSnapshot_onCacheMiss() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CommercialParty p = party(partyId);

        when(cacheManager.getCache(CacheConfig.SNAPSHOT_CACHE)).thenReturn(cache);
        when(cache.get(partyId)).thenReturn(null);
        when(partyRepository.findByPartyId(partyId)).thenReturn(p);
        when(partyRelationshipRepository.findActiveByFromPartyId(partyId, LocalDate.of(2024, 1, 1)))
                .thenReturn(Collections.emptyList());

        CrmSnapshotDTO result = service.buildSnapshotForParty(partyId);

        assertThat(result).isNotNull();
        assertThat(result.getSnapshotMetadata().getSource()).isEqualTo("CRM_API");
        assertThat(result.getBillingRules()).isNotNull();
        assertThat(result.getBillingRules().isPoRequired()).isFalse();
        assertThat(result.getBillingRules().getPaymentTerms()).isEqualTo("Due on Receipt");
        assertThat(result.getAccount()).isNotNull();
        verify(cache).put(partyId, result);
    }

    @Test
    void buildSnapshotForParty_returnsFromCache_onCacheHit() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        CrmSnapshotDTO cachedSnapshot = new CrmSnapshotDTO();
        SnapshotMetadata metadata = new SnapshotMetadata();
        metadata.setSource("CRM_API");
        cachedSnapshot.setSnapshotMetadata(metadata);

        Cache.ValueWrapper wrapper = mock(Cache.ValueWrapper.class);
        when(wrapper.get()).thenReturn(cachedSnapshot);
        when(cacheManager.getCache(CacheConfig.SNAPSHOT_CACHE)).thenReturn(cache);
        when(cache.get(partyId)).thenReturn(wrapper);

        CrmSnapshotDTO result = service.buildSnapshotForParty(partyId);

        assertThat(result).isNotNull();
        assertThat(result.getSnapshotMetadata().getSource()).isEqualTo("CACHE");
        verify(partyRepository, never()).findByPartyId(any());
    }

    @Test
    void buildSnapshotForParty_handlesNullCache_gracefully() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CommercialParty p = party(partyId);

        when(cacheManager.getCache(CacheConfig.SNAPSHOT_CACHE)).thenReturn(null);
        when(partyRepository.findByPartyId(partyId)).thenReturn(p);
        when(partyRelationshipRepository.findActiveByFromPartyId(partyId, LocalDate.of(2024, 1, 1)))
                .thenReturn(Collections.emptyList());

        CrmSnapshotDTO result = service.buildSnapshotForParty(partyId);

        assertThat(result).isNotNull();
        assertThat(result.getSnapshotMetadata().getSource()).isEqualTo("CRM_API");
    }

    @Test
    void buildSnapshotForParty_handlesContactsWithPhoneAndEmail() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID relId = UUID.fromString("00000000-0000-0000-0000-000000000009");
        CommercialParty p = party(partyId);

        UUID personId = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
        PersonParty person = new PersonParty();
        person.setPersonId(personId);

        PartyRelationship rel = new PartyRelationship();
        rel.setPartyRelationshipId(relId);
        rel.setToPerson(person);
        rel.setPrimaryBillingContact(false);

        when(cacheManager.getCache(CacheConfig.SNAPSHOT_CACHE)).thenReturn(cache);
        when(cache.get(partyId)).thenReturn(null);
        when(partyRepository.findByPartyId(partyId)).thenReturn(p);
        when(partyRelationshipRepository.findActiveByFromPartyId(partyId, LocalDate.of(2024, 1, 1)))
                .thenReturn(List.of(rel));
        when(peopleClient.fetchPersonIdentitiesQuietly(java.util.Set.of(personId)))
                .thenReturn(java.util.Map.of(personId, identity(personId, "Jane", "Doe", "jane@acme.com", "555-1234")));

        CrmSnapshotDTO result = service.buildSnapshotForParty(partyId);

        assertThat(result).isNotNull();
        assertThat(result.getContacts()).hasSize(1);
        assertThat(result.getContacts().getFirst().getName()).isEqualTo("Jane Doe");
        assertThat(result.getContacts().getFirst().getPhoneNumbers()).hasSize(1);
        assertThat(result.getContacts().getFirst().getEmailAddresses()).hasSize(1);
    }

    @Test
    void buildSnapshotForParty_usesLegalName_whenDisplayNameNullOrBlank() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CommercialParty p = party(partyId);
        p.setDisplayName(" ");
        p.setLegalName("Legal Corp");

        when(cacheManager.getCache(CacheConfig.SNAPSHOT_CACHE)).thenReturn(cache);
        when(cache.get(partyId)).thenReturn(null);
        when(partyRepository.findByPartyId(partyId)).thenReturn(p);
        when(partyRelationshipRepository.findActiveByFromPartyId(partyId, LocalDate.of(2024, 1, 1)))
                .thenReturn(Collections.emptyList());

        CrmSnapshotDTO result = service.buildSnapshotForParty(partyId);

        assertThat(result.getAccount().getAccountName()).isEqualTo("Legal Corp");
    }

    @Test
    void buildSnapshotForParty_throwsIllegalState_whenCustomerNumberMissing() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CommercialParty p = party(partyId);
        p.setCustomerNumber(" ");

        when(cacheManager.getCache(CacheConfig.SNAPSHOT_CACHE)).thenReturn(cache);
        when(cache.get(partyId)).thenReturn(null);
        when(partyRepository.findByPartyId(partyId)).thenReturn(p);

        assertThatThrownBy(() -> service.buildSnapshotForParty(partyId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("customerNumber");
    }

    @Test
    void buildSnapshotForParty_throwsIllegalState_whenLegalNameMissing() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CommercialParty p = party(partyId);
        p.setLegalName(" ");

        when(cacheManager.getCache(CacheConfig.SNAPSHOT_CACHE)).thenReturn(cache);
        when(cache.get(partyId)).thenReturn(null);
        when(partyRepository.findByPartyId(partyId)).thenReturn(p);

        assertThatThrownBy(() -> service.buildSnapshotForParty(partyId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("legalName");
    }

    @Test
    void buildSnapshotForParty_throwsIllegalState_whenPartyTypeMissing() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CommercialParty p = party(partyId);
        p.setPartyType(null);

        when(cacheManager.getCache(CacheConfig.SNAPSHOT_CACHE)).thenReturn(cache);
        when(cache.get(partyId)).thenReturn(null);
        when(partyRepository.findByPartyId(partyId)).thenReturn(p);

        assertThatThrownBy(() -> service.buildSnapshotForParty(partyId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("partyType");
    }

    @Test
    void buildSnapshotForParty_populatesVehicleSummaries_fromVehicleInventory() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID vehicleId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        CommercialParty p = party(partyId);
        p.getVehicleVins().add("VIN-123");

        VehicleResponse vehicleResponse = VehicleResponse.builder()
                .vehicleId(vehicleId)
                .vin("VIN-123")
                .licensePlate("ABC-123")
                .make("Ford")
                .model("F-150")
                .year(2024)
                .build();

        when(cacheManager.getCache(CacheConfig.SNAPSHOT_CACHE)).thenReturn(cache);
        when(cache.get(partyId)).thenReturn(null);
        when(partyRepository.findByPartyId(partyId)).thenReturn(p);
        when(partyRelationshipRepository.findActiveByFromPartyId(partyId, LocalDate.of(2024, 1, 1)))
                .thenReturn(Collections.emptyList());
        when(vehicleInventoryClient.getVehicleByVin("VIN-123")).thenReturn(Optional.of(vehicleResponse));

        CrmSnapshotDTO result = service.buildSnapshotForParty(partyId);

        assertThat(result).isNotNull();
        assertThat(result.getVehicles()).hasSize(1);
        assertThat(result.getVehicles().getFirst().getVehicleId()).isEqualTo(vehicleId.toString());
        assertThat(result.getVehicles().getFirst().getVin()).isEqualTo("VIN-123");
        assertThat(result.getVehicles().getFirst().getMake()).isEqualTo("Ford");
        assertThat(result.getVehicles().getFirst().getModel()).isEqualTo("F-150");
        assertThat(result.getVehicles().getFirst().getYear()).isEqualTo(2024);
    }

    @Test
    void findPartyById_delegatesToRepository() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CommercialParty p = party(partyId);
        when(partyRepository.findByPartyId(partyId)).thenReturn(p);

        CommercialParty result = service.findPartyById(partyId);

        assertThat(result).isSameAs(p);
    }

    @Test
    void createCommercialAccount_throwsBadRequest_whenLegalNameMissing() {
        CreateCommercialAccountRequest request = new CreateCommercialAccountRequest();
        request.setLegalName(" ");

        assertThatThrownBy(() -> service.createCommercialAccount(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST")
                .hasMessageContaining("legalName is required");
    }

    @Test
    void createCommercialAccount_savesParty() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CreateCommercialAccountRequest request = new CreateCommercialAccountRequest();
        request.setLegalName("Acme Legal");
        request.setDisplayName("Acme Display");
        request.setTaxId("TAX-1");
        request.setBillingTermsId("NET30");
        request.setExternalIdentifiers(new HashMap<>(Collections.singletonMap("erp", "A-100")));

        CommercialParty saved = party(partyId);
        saved.setLegalName("Acme Legal");
        saved.setCreatedAt(Instant.now(TEST_CLOCK));
        when(partyRepository.save(any(CommercialParty.class))).thenReturn(saved);

        var response = service.createCommercialAccount(request);

        assertThat(response.getPartyId()).isEqualTo(partyId.toString());
        assertThat(response.getLegalName()).isEqualTo("Acme Legal");
        assertThat(response.getStatus()).isEqualTo(AccountStatus.ACTIVE.toString());

        ArgumentCaptor<CommercialParty> partyCaptor = ArgumentCaptor.forClass(CommercialParty.class);
        verify(partyRepository).save(partyCaptor.capture());
        assertThat(partyCaptor.getValue().getPartyType()).isEqualTo(PartyType.COMMERCIAL);
        assertThat(partyCaptor.getValue().getCustomerNumber()).startsWith("CUST-");
        assertThat(partyCaptor.getValue().getExternalIdentifiers()).containsEntry("erp", "A-100");
    }

    @Test
    void getParty_returnsMappedResponse() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CommercialParty p = party(partyId);
        p.setTaxId("TX-1");
        p.setBillingTermsId("NET45");
        when(partyRepository.findByPartyId(partyId)).thenReturn(p);

        GetPartyResponse response = service.getParty(partyId);

        assertThat(response.getPartyId()).isEqualTo(partyId.toString());
        assertThat(response.getPartyType()).isEqualTo("COMMERCIAL");
        assertThat(response.getTaxId()).isEqualTo("TX-1");
        assertThat(response.getBillingTermsId()).isEqualTo("NET45");
    }

    @Test
    void getParty_returnsMappedResponse_forPersonParty() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000021");
        UUID personId = UUID.fromString("00000000-0000-0000-0000-000000000022");
        PersonParty personParty = personParty(partyId, personId);

        when(partyRepository.findByPartyId(partyId)).thenReturn(null);
        when(personPartyRepository.findById(partyId)).thenReturn(Optional.of(personParty));
        when(peopleClient.fetchPersonIdentitiesQuietly(java.util.Set.of(personId)))
                .thenReturn(java.util.Map.of(personId, identity(personId, "Pat", "Person", null, null)));

        GetPartyResponse response = service.getParty(partyId);

        assertThat(response.getPartyId()).isEqualTo(partyId.toString());
        assertThat(response.getPartyType()).isEqualTo("PERSON");
        assertThat(response.getLegalName()).isEqualTo("Pat Person");
        assertThat(response.getDisplayName()).isEqualTo("Pat Person");
        assertThat(response.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void getParty_throwsNotFound_whenMissing() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(partyRepository.findByPartyId(partyId)).thenReturn(null);
        when(personPartyRepository.findById(partyId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getParty(partyId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND")
                .hasMessageContaining("party not found");
    }

    @Test
    void searchParties_filtersByNameAndTaxId() {
        CommercialParty match = party(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        match.setLegalName("Acme Industrial");
        match.setDisplayName("Acme");
        match.setTaxId("T1");

        CommercialParty miss = party(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        miss.setLegalName("Other Corp");
        miss.setDisplayName("Other");
        miss.setTaxId("T2");

        when(partyRepository.findAll()).thenReturn(List.of(match, miss));

        SearchPartiesRequest request = new SearchPartiesRequest();
        request.setName("acme");
        request.setTaxId("T1");

        SearchPartiesResponse response = service.searchParties(request);

        assertThat(response.getTotalCount()).isEqualTo(1);
        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().getFirst().getLegalName()).isEqualTo("Acme Industrial");
    }

    @Test
    void browseParties_usesDefaultPageAndSort_whenPageableIsUnpaged() {
        CommercialParty first = party(UUID.fromString("00000000-0000-0000-0000-000000000101"));
        first.setLegalName("Acme Corp");
        first.setDisplayName("Acme Corp");

        when(partyRepository.findAll()).thenReturn(List.of(first));
        when(personPartyRepository.findIndividualCustomers()).thenReturn(List.of());

        SearchPartiesResponse response = service.browseParties(Pageable.unpaged());

        assertThat(response.getTotalCount()).isEqualTo(1);
        assertThat(response.getPageNumber()).isEqualTo(0);
        assertThat(response.getPageSize()).isEqualTo(20);
        assertThat(response.getResults())
                .extracting(SearchPartiesResponse.PartySummary::getLegalName)
                .containsExactly("Acme Corp");
    }

    @Test
    void browseParties_returnsEmptyResultsWithPagingMetadata() {
        when(partyRepository.findAll()).thenReturn(List.of());
        when(personPartyRepository.findIndividualCustomers()).thenReturn(List.of());

        SearchPartiesResponse response = service.browseParties(Pageable.unpaged());

        assertThat(response.getResults()).isEmpty();
        assertThat(response.getTotalCount()).isZero();
        assertThat(response.getPageNumber()).isEqualTo(0);
        assertThat(response.getPageSize()).isEqualTo(20);
    }

    @Test
    void browseParties_mergesCommercialAndIndividualCustomers_sortedByDisplayName() {
        CommercialParty commercial = party(UUID.fromString("00000000-0000-0000-0000-0000000000c1"));
        commercial.setLegalName("Zenith Freight LLC");
        commercial.setDisplayName("Zenith Freight LLC");

        UUID aliceId = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
        PersonParty individual = new PersonParty();
        individual.setPartyId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"));
        individual.setPersonId(aliceId);
        individual.setStatus(AccountStatus.ACTIVE);

        when(partyRepository.findAll()).thenReturn(List.of(commercial));
        when(personPartyRepository.findIndividualCustomers()).thenReturn(List.of(individual));
        lenient()
                .when(peopleClient.fetchPersonIdentitiesQuietly(java.util.Set.of(aliceId)))
                .thenReturn(java.util.Map.of(aliceId, identity(aliceId, "Alice", "Anders", null, null)));

        SearchPartiesResponse response = service.browseParties(Pageable.unpaged());

        assertThat(response.getTotalCount()).isEqualTo(2);
        // Sorted case-insensitively by display name: "Alice Anders" before "Zenith Freight LLC"
        assertThat(response.getResults())
                .extracting(SearchPartiesResponse.PartySummary::getPartyType)
                .containsExactly(PartyType.PERSON.toString(), PartyType.COMMERCIAL.toString());
        assertThat(response.getResults().get(0).getDisplayName()).isEqualTo("Alice Anders");
    }

    private CommercialParty browseable(String idSuffix, String legalName, String customerNumber, AccountStatus status) {
        CommercialParty p = party(UUID.fromString("00000000-0000-0000-0000-0000000000" + idSuffix));
        p.setLegalName(legalName);
        p.setDisplayName(legalName);
        p.setCustomerNumber(customerNumber);
        p.setStatus(status);
        return p;
    }

    @Test
    void browseParties_filtersByName_caseInsensitiveContainsOnDisplayOrLegal() {
        when(partyRepository.findAll())
                .thenReturn(List.of(
                        browseable("01", "Acme Industrial", "CUST-1", AccountStatus.ACTIVE),
                        browseable("02", "Beta Supplies", "CUST-2", AccountStatus.ACTIVE)));
        when(personPartyRepository.findIndividualCustomers()).thenReturn(List.of());

        SearchPartiesResponse response =
                service.browseParties(Pageable.unpaged(), "acme", null, null, null, null, null);

        assertThat(response.getTotalCount()).isEqualTo(1);
        assertThat(response.getResults())
                .extracting(SearchPartiesResponse.PartySummary::getLegalName)
                .containsExactly("Acme Industrial");
    }

    @Test
    void browseParties_nameTerm_alsoMatchesCustomerNumber() {
        when(partyRepository.findAll())
                .thenReturn(List.of(
                        browseable("01", "Acme Industrial", "CUST-100", AccountStatus.ACTIVE),
                        browseable("02", "Beta Supplies", "CUST-200", AccountStatus.ACTIVE)));
        when(personPartyRepository.findIndividualCustomers()).thenReturn(List.of());

        // A single typeahead term routed through `name` finds a customer by account number.
        SearchPartiesResponse response =
                service.browseParties(Pageable.unpaged(), "cust-200", null, null, null, null, null);

        assertThat(response.getResults())
                .extracting(SearchPartiesResponse.PartySummary::getLegalName)
                .containsExactly("Beta Supplies");
    }

    @Test
    void browseParties_filtersByStatusAndCustomerNumber() {
        when(partyRepository.findAll())
                .thenReturn(List.of(
                        browseable("01", "Acme", "CUST-100", AccountStatus.ACTIVE),
                        browseable("02", "Beta", "CUST-200", AccountStatus.INACTIVE)));
        when(personPartyRepository.findIndividualCustomers()).thenReturn(List.of());

        assertThat(service.browseParties(Pageable.unpaged(), null, "INACTIVE", null, null, null, null)
                        .getResults())
                .extracting(SearchPartiesResponse.PartySummary::getLegalName)
                .containsExactly("Beta");

        assertThat(service.browseParties(Pageable.unpaged(), null, null, null, "cust-100", null, null)
                        .getResults())
                .extracting(SearchPartiesResponse.PartySummary::getCustomerNumber)
                .containsExactly("CUST-100");
    }

    @Test
    void browseParties_filterAppliedBeforePaging_totalCountReflectsFilteredSize() {
        when(partyRepository.findAll())
                .thenReturn(List.of(
                        browseable("01", "Acme One", "C1", AccountStatus.ACTIVE),
                        browseable("02", "Acme Two", "C2", AccountStatus.ACTIVE),
                        browseable("03", "Other Co", "C3", AccountStatus.ACTIVE)));
        when(personPartyRepository.findIndividualCustomers()).thenReturn(List.of());

        SearchPartiesResponse response =
                service.browseParties(PageRequest.of(0, 1), "acme", null, null, null, null, null);

        // 2 match the filter; page size 1 -> one row, but totalCount is the filtered count.
        assertThat(response.getTotalCount()).isEqualTo(2);
        assertThat(response.getResults()).hasSize(1);
    }

    @Test
    void browseParties_sortsByCustomerNumber_descending() {
        when(partyRepository.findAll())
                .thenReturn(List.of(
                        browseable("01", "Acme", "CUST-001", AccountStatus.ACTIVE),
                        browseable("02", "Beta", "CUST-003", AccountStatus.ACTIVE),
                        browseable("03", "Gamma", "CUST-002", AccountStatus.ACTIVE)));
        when(personPartyRepository.findIndividualCustomers()).thenReturn(List.of());

        SearchPartiesResponse response =
                service.browseParties(Pageable.unpaged(), null, null, null, null, "customerNumber", "desc");

        assertThat(response.getResults())
                .extracting(SearchPartiesResponse.PartySummary::getCustomerNumber)
                .containsExactly("CUST-003", "CUST-002", "CUST-001");
    }

    @Test
    void browseParties_nullFilterFields_doNotMatchOrThrow() {
        CommercialParty noCustNo = browseable("01", "Acme", null, AccountStatus.ACTIVE);
        when(partyRepository.findAll()).thenReturn(List.of(noCustNo));
        when(personPartyRepository.findIndividualCustomers()).thenReturn(List.of());

        // filtering by customerNumber excludes the row whose customerNumber is null, no NPE
        assertThat(service.browseParties(Pageable.unpaged(), null, null, null, "C", null, null)
                        .getResults())
                .isEmpty();
    }

    @Test
    void browseParties_individualName_sourcedFromPosPeople_overridesLocalCopy() {
        UUID canonicalPersonId = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
        PersonParty individual = new PersonParty();
        individual.setPartyId(UUID.fromString("00000000-0000-0000-0000-0000000000a2"));
        individual.setPersonId(canonicalPersonId);
        individual.setStatus(AccountStatus.ACTIVE);

        when(partyRepository.findAll()).thenReturn(List.of());
        when(personPartyRepository.findIndividualCustomers()).thenReturn(List.of(individual));
        when(peopleClient.fetchPersonIdentitiesQuietly(java.util.Set.of(canonicalPersonId)))
                .thenReturn(java.util.Map.of(
                        canonicalPersonId,
                        new PeopleClient.PersonIdentity(
                                canonicalPersonId, "Fresh", "Canonical", "f@x.com", java.util.List.of())));

        SearchPartiesResponse response = service.browseParties(Pageable.unpaged());

        // Name comes from pos-people (source of truth), not the local person_party copy.
        assertThat(response.getResults().get(0).getDisplayName()).isEqualTo("Fresh Canonical");
    }

    @Test
    void browseParties_posPeopleUnreachable_degradesNameToNull_doesNotFail() {
        // Regression guard (issue #684 review): with the local name fallback removed,
        // a pos-people outage must degrade the directory listing (null names) rather than
        // failing the whole request. The read path uses the resilient quiet fetch.
        UUID canonicalId = UUID.fromString("00000000-0000-0000-0000-0000000000d5");
        PersonParty individual = new PersonParty();
        individual.setPartyId(UUID.fromString("00000000-0000-0000-0000-0000000000a5"));
        individual.setPersonId(canonicalId);
        individual.setStatus(AccountStatus.ACTIVE);

        when(partyRepository.findAll()).thenReturn(List.of());
        when(personPartyRepository.findIndividualCustomers()).thenReturn(List.of(individual));
        when(peopleClient.fetchPersonIdentitiesQuietly(java.util.Set.of(canonicalId)))
                .thenReturn(java.util.Map.of());

        SearchPartiesResponse response = service.browseParties(Pageable.unpaged());

        assertThat(response.getTotalCount()).isEqualTo(1);
        assertThat(response.getResults().getFirst().getDisplayName()).isNull();
    }

    @Test
    void browseParties_populatesPrimaryContact_forCommercialParty() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
        UUID relId = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
        CommercialParty commercial = party(partyId);
        commercial.setLegalName("Acme Corp");
        commercial.setDisplayName("Acme Corp");

        UUID contactPersonId = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
        PersonParty contact = new PersonParty();
        contact.setPersonId(contactPersonId);

        PartyRelationship rel = new PartyRelationship();
        rel.setPartyRelationshipId(relId);
        rel.setToPerson(contact);
        rel.setPrimaryBillingContact(true);

        when(partyRepository.findAll()).thenReturn(List.of(commercial));
        when(personPartyRepository.findIndividualCustomers()).thenReturn(List.of());
        when(partyRelationshipRepository.findActiveByFromPartyId(partyId, LocalDate.of(2024, 1, 1)))
                .thenReturn(List.of(rel));
        when(peopleClient.fetchPersonIdentitiesQuietly(java.util.Set.of(contactPersonId)))
                .thenReturn(java.util.Map.of(
                        contactPersonId, identity(contactPersonId, "Jane", "Doe", "jane@acme.com", "555-1234")));

        SearchPartiesResponse response = service.browseParties(Pageable.unpaged());

        SearchPartiesResponse.PrimaryContact primary =
                response.getResults().getFirst().getPrimaryContact();
        assertThat(primary).isNotNull();
        assertThat(primary.getName()).isEqualTo("Jane Doe");
        assertThat(primary.getEmail()).isEqualTo("jane@acme.com");
        assertThat(primary.getPhone()).isEqualTo("555-1234");
    }

    @Test
    void browseParties_populatesPrimaryContact_forIndividualCustomer() {
        UUID aliceId = UUID.fromString("00000000-0000-0000-0000-0000000000b3");
        PersonParty individual = new PersonParty();
        individual.setPartyId(UUID.fromString("00000000-0000-0000-0000-0000000000a3"));
        individual.setPersonId(aliceId);
        individual.setStatus(AccountStatus.ACTIVE);

        when(partyRepository.findAll()).thenReturn(List.of());
        when(personPartyRepository.findIndividualCustomers()).thenReturn(List.of(individual));
        when(peopleClient.fetchPersonIdentitiesQuietly(java.util.Set.of(aliceId)))
                .thenReturn(java.util.Map.of(aliceId, identity(aliceId, "Alice", "Anders", "alice@example.com", null)));

        SearchPartiesResponse response = service.browseParties(Pageable.unpaged());

        SearchPartiesResponse.PrimaryContact primary =
                response.getResults().getFirst().getPrimaryContact();
        assertThat(primary).isNotNull();
        assertThat(primary.getName()).isEqualTo("Alice Anders");
        assertThat(primary.getEmail()).isEqualTo("alice@example.com");
    }

    @Test
    void browseParties_populatesVehicleCount_fromPartyVins() {
        CommercialParty commercial = party(UUID.fromString("00000000-0000-0000-0000-0000000000c3"));
        commercial.setLegalName("Fleet Co");
        commercial.setDisplayName("Fleet Co");
        commercial.setVehicleVins(new java.util.HashSet<>(List.of("VIN1", "VIN2", "VIN3")));

        UUID noVehId = UUID.fromString("00000000-0000-0000-0000-0000000000b4");
        PersonParty individual = new PersonParty();
        individual.setPartyId(UUID.fromString("00000000-0000-0000-0000-0000000000a4"));
        individual.setPersonId(noVehId);
        individual.setStatus(AccountStatus.ACTIVE);

        when(partyRepository.findAll()).thenReturn(List.of(commercial));
        when(personPartyRepository.findIndividualCustomers()).thenReturn(List.of(individual));
        lenient()
                .when(peopleClient.fetchPersonIdentitiesQuietly(java.util.Set.of(noVehId)))
                .thenReturn(java.util.Map.of(noVehId, identity(noVehId, "No", "Vehicles", null, null)));

        SearchPartiesResponse response = service.browseParties(Pageable.unpaged());

        java.util.Map<String, Integer> countByName = response.getResults().stream()
                .collect(java.util.stream.Collectors.toMap(
                        SearchPartiesResponse.PartySummary::getDisplayName,
                        SearchPartiesResponse.PartySummary::getVehicleCount));
        assertThat(countByName.get("Fleet Co")).isEqualTo(3);
        assertThat(countByName.get("No Vehicles")).isZero();
    }

    @Test
    void searchParties_filtersByPartyTypeAndStatus_caseInsensitive() {
        CommercialParty match = party(UUID.fromString("00000000-0000-0000-0000-000000000003"));
        match.setPartyType(PartyType.COMMERCIAL);
        match.setStatus(AccountStatus.ACTIVE);

        CommercialParty miss = party(UUID.fromString("00000000-0000-0000-0000-000000000004"));
        miss.setPartyType(PartyType.PERSON);
        miss.setStatus(AccountStatus.INACTIVE);

        when(partyRepository.findAll()).thenReturn(List.of(match, miss));

        SearchPartiesRequest request = new SearchPartiesRequest();
        request.setPartyType("commercial");
        request.setStatus("active");

        SearchPartiesResponse response = service.searchParties(request);

        assertThat(response.getTotalCount()).isEqualTo(1);
        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().getFirst().getPartyId())
                .isEqualTo(match.getPartyId().toString());
    }

    @Test
    void searchParties_returnsNoResults_whenPartyTypeFilterInvalid() {
        CommercialParty existing = party(UUID.fromString("00000000-0000-0000-0000-000000000005"));
        when(partyRepository.findAll()).thenReturn(List.of(existing));

        SearchPartiesRequest request = new SearchPartiesRequest();
        request.setPartyType("NOT_A_VALID_TYPE");

        SearchPartiesResponse response = service.searchParties(request);

        assertThat(response.getTotalCount()).isZero();
        assertThat(response.getResults()).isEmpty();
    }

    @Test
    void mergeParties_mergesIdentifiersAndVins_reassignsRelationships() {
        UUID survivorId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID loserId = UUID.fromString("00000000-0000-0000-0000-000000000009");
        CommercialParty survivor = party(survivorId);
        CommercialParty loser = party(loserId);
        loser.getExternalIdentifiers().put("legacy", "L-1");
        loser.getVehicleVins().add("VIN-MERGE");

        when(partyRepository.findByPartyId(survivorId)).thenReturn(survivor);
        when(partyRepository.findByPartyId(loserId)).thenReturn(loser);

        MergePartiesRequest request = new MergePartiesRequest();
        request.setLosingPartyId(loserId.toString());
        request.setJustification("duplicate");

        MergePartiesResponse response = service.mergeParties(survivorId, request);

        assertThat(response.getStatus()).isEqualTo("COMPLETED");
        assertThat(survivor.getExternalIdentifiers()).containsEntry("legacy", "L-1");
        assertThat(survivor.getVehicleVins()).contains("VIN-MERGE");
        assertThat(loser.getStatus()).isEqualTo(AccountStatus.MERGED);
        verify(partyRelationshipRepository).reassignFromParty(loserId, survivorId);
        verify(partyRepository).save(survivor);
        verify(partyRepository).save(loser);
    }

    @Test
    void mergeParties_throwsBadRequest_forInvalidLosingPartyId() {
        UUID survivorId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CommercialParty survivor = party(survivorId);
        when(partyRepository.findByPartyId(survivorId)).thenReturn(survivor);

        MergePartiesRequest request = new MergePartiesRequest();
        request.setLosingPartyId("not-a-uuid");
        request.setJustification("duplicate");

        assertThatThrownBy(() -> service.mergeParties(survivorId, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST")
                .hasMessageContaining("Invalid UUID format");
    }

    @Test
    void mergeParties_throwsBadRequest_whenMergingSelf() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CommercialParty party = party(partyId);
        when(partyRepository.findByPartyId(partyId)).thenReturn(party);

        MergePartiesRequest request = new MergePartiesRequest();
        request.setLosingPartyId(partyId.toString());
        request.setJustification("duplicate");

        assertThatThrownBy(() -> service.mergeParties(partyId, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Cannot merge party with itself");
    }

    @Test
    void getCommunicationPreferences_returnsDefaults() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CommercialParty p = party(partyId);
        when(partyRepository.findByPartyId(partyId)).thenReturn(p);

        GetCommunicationPreferencesResponse response = service.getCommunicationPreferences(partyId);

        assertThat(response.getPartyId()).isEqualTo(partyId.toString());
        assertThat(response.getEmailPreference()).isEqualTo("NOT_APPLICABLE");
        assertThat(response.getSmsPreference()).isEqualTo("NOT_APPLICABLE");
        assertThat(response.getPhonePreference()).isEqualTo("NOT_APPLICABLE");
    }

    @Test
    void upsertCommunicationPreferences_throwsBadRequest_whenBodyNull() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(partyRepository.findByPartyId(partyId)).thenReturn(party(partyId));

        assertThatThrownBy(() -> service.upsertCommunicationPreferences(partyId, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST")
                .hasMessageContaining("request body is required");
    }

    @Test
    void upsertCommunicationPreferences_returnsSuccess_whenBodyPresent() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(partyRepository.findByPartyId(partyId)).thenReturn(party(partyId));

        UpsertCommunicationPreferencesResponse response =
                service.upsertCommunicationPreferences(partyId, new UpsertCommunicationPreferencesRequest());

        assertThat(response.getPartyId()).isEqualTo(partyId.toString());
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void createVehicleForParty_throwsBadRequest_whenVinMissing() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CreateVehicleForPartyRequest request = new CreateVehicleForPartyRequest();

        assertThatThrownBy(() -> service.createVehicleForParty(partyId, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST")
                .hasMessageContaining("vinNumber is required");
    }

    @Test
    void createVehicleForParty_throwsConflict_whenVinAlreadyAssociated() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CommercialParty p = party(partyId);
        p.getVehicleVins().add("VIN-1");
        when(partyRepository.findByPartyId(partyId)).thenReturn(p);

        CreateVehicleForPartyRequest request = new CreateVehicleForPartyRequest();
        request.setVinNumber("VIN-1");

        assertThatThrownBy(() -> service.createVehicleForParty(partyId, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409 CONFLICT")
                .hasMessageContaining("Vehicle already associated with this party");
    }

    @Test
    void createVehicleForParty_addsVinAndSaves() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CommercialParty p = party(partyId);
        when(partyRepository.findByPartyId(partyId)).thenReturn(p);

        CreateVehicleForPartyRequest request = new CreateVehicleForPartyRequest();
        request.setVinNumber("VIN-NEW");

        var response = service.createVehicleForParty(partyId, request);

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getVinNumber()).isEqualTo("VIN-NEW");
        assertThat(p.getVehicleVins()).contains("VIN-NEW");
        verify(partyRepository).save(p);
    }

    @Test
    void getBillingRulesForParty_returnsDefaults_whenPartyExists() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CommercialParty p = party(partyId);
        when(partyRepository.findByPartyId(partyId)).thenReturn(p);

        var result = service.getBillingRulesForParty(partyId);

        assertThat(result).isNotNull();
        assertThat(result.isPoRequired()).isFalse();
        assertThat(result.getPaymentTerms()).isEqualTo("Due on Receipt");
    }

    @Test
    void getBillingRulesForParty_returnsNull_whenPartyNotFound() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(partyRepository.findByPartyId(partyId)).thenReturn(null);
        when(personPartyRepository.existsById(partyId)).thenReturn(false);

        var result = service.getBillingRulesForParty(partyId);

        assertThat(result).isNull();
    }

    @Test
    void getBillingRulesForParty_returnsDefaults_whenPersonPartyExists() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000031");
        when(partyRepository.findByPartyId(partyId)).thenReturn(null);
        when(personPartyRepository.existsById(partyId)).thenReturn(true);

        var result = service.getBillingRulesForParty(partyId);

        assertThat(result).isNotNull();
        assertThat(result.isPoRequired()).isFalse();
        assertThat(result.getPaymentTerms()).isEqualTo("Due on Receipt");
    }

    @Test
    void buildSnapshotForParty_returnsFreshSnapshot_forPersonParty() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000041");
        UUID personId = UUID.fromString("00000000-0000-0000-0000-000000000042");
        PersonParty personParty = personParty(partyId, personId);
        personParty.setVehicleVins(new HashSet<>(List.of("VIN-P-1")));

        VehicleResponse vehicleResponse = VehicleResponse.builder()
                .vehicleId(UUID.fromString("00000000-0000-0000-0000-000000000043"))
                .vin("VIN-P-1")
                .make("Toyota")
                .model("Camry")
                .year(2025)
                .build();

        when(cacheManager.getCache(CacheConfig.SNAPSHOT_CACHE)).thenReturn(cache);
        when(cache.get(partyId)).thenReturn(null);
        when(partyRepository.findByPartyId(partyId)).thenReturn(null);
        when(personPartyRepository.findById(partyId)).thenReturn(Optional.of(personParty));
        when(peopleClient.fetchPersonIdentitiesQuietly(java.util.Set.of(personId)))
                .thenReturn(java.util.Map.of(personId, identity(personId, "Sam", "Solo", null, null)));
        when(vehicleInventoryClient.getVehicleByVin("VIN-P-1")).thenReturn(Optional.of(vehicleResponse));

        CrmSnapshotDTO result = service.buildSnapshotForParty(partyId);

        assertThat(result).isNotNull();
        assertThat(result.getAccount().getPartyId()).isEqualTo(partyId.toString());
        assertThat(result.getAccount().getAccountType()).isEqualTo("PERSON");
        assertThat(result.getAccount().getAccountName()).isEqualTo("Sam Solo");
        assertThat(result.getVehicles()).hasSize(1);
    }

    @Test
    void getBillingRulesForParty_overlaysDefaults_whenEmbeddedHasOnlyPaymentTerms() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        CommercialParty p = party(partyId);
        BillingRulesEmbeddable embedded = new BillingRulesEmbeddable();
        embedded.setPaymentTerms("Net 60");
        p.setBillingRules(embedded);
        when(partyRepository.findByPartyId(partyId)).thenReturn(p);

        var result = service.getBillingRulesForParty(partyId);

        assertThat(result).isNotNull();
        assertThat(result.getPaymentTerms()).isEqualTo("Net 60");
        assertThat(result.getInvoiceDeliveryMethod()).isEqualTo(InvoiceDeliveryMethod.EMAIL);
        assertThat(result.isPoRequired()).isFalse();
        assertThat(result.isTaxExempt()).isFalse();
        assertThat(result.isCreditHold()).isFalse();
        assertThat(result.isAutoPayEnabled()).isFalse();
    }
}
