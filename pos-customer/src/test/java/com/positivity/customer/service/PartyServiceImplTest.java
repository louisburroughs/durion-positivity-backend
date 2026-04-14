package com.positivity.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.positivity.customer.internal.dto.GetContactsWithRolesResponse;
import com.positivity.customer.internal.dto.GetPartyResponse;
import com.positivity.customer.internal.dto.MergePartiesRequest;
import com.positivity.customer.internal.dto.MergePartiesResponse;
import com.positivity.customer.internal.dto.SearchPartiesRequest;
import com.positivity.customer.internal.dto.SearchPartiesResponse;
import com.positivity.customer.internal.dto.UpdateContactRolesRequest;
import com.positivity.customer.internal.dto.UpdateContactRolesResponse;
import com.positivity.customer.internal.dto.UpsertCommunicationPreferencesRequest;
import com.positivity.customer.internal.dto.UpsertCommunicationPreferencesResponse;
import com.positivity.customer.internal.dto.snapshot.CrmSnapshotDTO;
import com.positivity.customer.internal.dto.snapshot.SnapshotMetadata;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.Contact;
import com.positivity.customer.internal.enums.AccountStatus;
import com.positivity.customer.internal.enums.PartyType;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.ContactRepository;
import com.positivity.customer.internal.service.PartyServiceImpl;
import com.positivity.shared.dto.VehicleResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class PartyServiceImplTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private CommercialPartyRepository partyRepository;

    @Mock
    private ContactRepository contactRepository;

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
                TEST_CLOCK, partyRepository, contactRepository, cacheManager, peopleClient, vehicleInventoryClient);
    }

    private CommercialParty party(UUID id) {
        CommercialParty p = new CommercialParty();
        p.setPartyId(id);
        p.setPartyNumber("PARTY-001");
        p.setLegalName("Acme Corp");
        p.setDisplayName("Acme");
        p.setPartyType(PartyType.COMMERCIAL);
        p.setStatus(AccountStatus.ACTIVE);
        p.setCreatedAt(Instant.now(TEST_CLOCK));
        p.setModifiedAt(Instant.now(TEST_CLOCK));
        p.setContacts(new HashSet<>());
        p.setVehicleVins(new HashSet<>());
        p.setExternalIdentifiers(new HashMap<>());
        return p;
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
        when(contactRepository.findByCommercialParty(p)).thenReturn(Collections.emptyList());

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
        when(contactRepository.findByCommercialParty(p)).thenReturn(Collections.emptyList());

        CrmSnapshotDTO result = service.buildSnapshotForParty(partyId);

        assertThat(result).isNotNull();
        assertThat(result.getSnapshotMetadata().getSource()).isEqualTo("CRM_API");
    }

    @Test
    void buildSnapshotForParty_handlesContactsWithPhoneAndEmail() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CommercialParty p = party(partyId);

        Contact c = new Contact();
        c.setId(UUID.fromString("00000000-0000-0000-0000-000000000009"));
        c.setFirstName("Jane");
        c.setLastName("Doe");
        c.setPhoneNumber("555-1234");
        c.setEmail("jane@acme.com");
        c.setCommercialParty(p);

        when(cacheManager.getCache(CacheConfig.SNAPSHOT_CACHE)).thenReturn(cache);
        when(cache.get(partyId)).thenReturn(null);
        when(partyRepository.findByPartyId(partyId)).thenReturn(p);
        when(contactRepository.findByCommercialParty(p)).thenReturn(List.of(c));

        CrmSnapshotDTO result = service.buildSnapshotForParty(partyId);

        assertThat(result).isNotNull();
        assertThat(result.getContacts()).hasSize(1);
        assertThat(result.getContacts().getFirst().getName()).isEqualTo("Jane Doe");
        assertThat(result.getContacts().getFirst().getPhoneNumbers()).hasSize(1);
        assertThat(result.getContacts().getFirst().getEmailAddresses()).hasSize(1);
    }

    @Test
    void buildSnapshotForParty_usesContactId_whenContactNameMissing() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CommercialParty p = party(partyId);
        UUID contactId = UUID.fromString("00000000-0000-0000-0000-000000000009");

        Contact c = new Contact();
        c.setId(contactId);
        c.setFirstName(" ");
        c.setLastName(null);
        c.setCommercialParty(p);

        when(cacheManager.getCache(CacheConfig.SNAPSHOT_CACHE)).thenReturn(cache);
        when(cache.get(partyId)).thenReturn(null);
        when(partyRepository.findByPartyId(partyId)).thenReturn(p);
        when(contactRepository.findByCommercialParty(p)).thenReturn(List.of(c));

        CrmSnapshotDTO result = service.buildSnapshotForParty(partyId);

        assertThat(result.getContacts()).hasSize(1);
        assertThat(result.getContacts().getFirst().getName()).isEqualTo(contactId.toString());
    }

    @Test
    void buildSnapshotForParty_usesEmail_whenContactNameMissing() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CommercialParty p = party(partyId);

        Contact c = new Contact();
        c.setId(UUID.fromString("00000000-0000-0000-0000-000000000010"));
        c.setFirstName(" ");
        c.setLastName(" ");
        c.setEmail("fallback@acme.com");
        c.setCommercialParty(p);

        when(cacheManager.getCache(CacheConfig.SNAPSHOT_CACHE)).thenReturn(cache);
        when(cache.get(partyId)).thenReturn(null);
        when(partyRepository.findByPartyId(partyId)).thenReturn(p);
        when(contactRepository.findByCommercialParty(p)).thenReturn(List.of(c));

        CrmSnapshotDTO result = service.buildSnapshotForParty(partyId);

        assertThat(result.getContacts()).hasSize(1);
        assertThat(result.getContacts().getFirst().getName()).isEqualTo("fallback@acme.com");
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
        when(contactRepository.findByCommercialParty(p)).thenReturn(Collections.emptyList());

        CrmSnapshotDTO result = service.buildSnapshotForParty(partyId);

        assertThat(result.getAccount().getAccountName()).isEqualTo("Legal Corp");
    }

    @Test
    void buildSnapshotForParty_throwsIllegalState_whenPartyNumberMissing() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CommercialParty p = party(partyId);
        p.setPartyNumber(" ");

        when(cacheManager.getCache(CacheConfig.SNAPSHOT_CACHE)).thenReturn(cache);
        when(cache.get(partyId)).thenReturn(null);
        when(partyRepository.findByPartyId(partyId)).thenReturn(p);

        assertThatThrownBy(() -> service.buildSnapshotForParty(partyId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("partyNumber");
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
        when(contactRepository.findByCommercialParty(p)).thenReturn(Collections.emptyList());
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
    void findContactsByParty_delegatesToRepository() {
        CommercialParty p = party(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        Contact c = new Contact();
        c.setId(UUID.fromString("00000000-0000-0000-0000-000000000009"));
        c.setFirstName("Alex");
        c.setLastName("Rivera");
        when(contactRepository.findByCommercialParty(p)).thenReturn(List.of(c));

        List<Contact> result = service.findContactsByParty(p);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getFirstName()).isEqualTo("Alex");
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
    void createCommercialAccount_throwsIllegalArgument_whenContactLastNameMissing() {
        CreateCommercialAccountRequest request = new CreateCommercialAccountRequest();
        request.setLegalName("Acme Legal");
        request.setEmail("billing@acme.com");

        assertThatThrownBy(() -> service.createCommercialAccount(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("contactLastName is required");
    }

    @Test
    void createCommercialAccount_savesPartyAndContact() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CreateCommercialAccountRequest request = new CreateCommercialAccountRequest();
        request.setLegalName("Acme Legal");
        request.setDisplayName("Acme Display");
        request.setTaxId("TAX-1");
        request.setBillingTermsId("NET30");
        request.setContactFirstName("John");
        request.setContactLastName("Doe");
        request.setEmail("billing@acme.com");
        request.setPhone("555-0001");
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
        assertThat(partyCaptor.getValue().getPartyNumber()).startsWith("PARTY-");
        assertThat(partyCaptor.getValue().getExternalIdentifiers()).containsEntry("erp", "A-100");
        verify(contactRepository).save(any(Contact.class));
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
    void getParty_throwsNotFound_whenMissing() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(partyRepository.findByPartyId(partyId)).thenReturn(null);

        assertThatThrownBy(() -> service.getParty(partyId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND")
                .hasMessageContaining("party not found");
    }

    @Test
    void getContactsWithRoles_returnsContacts() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CommercialParty p = party(partyId);
        Contact c = new Contact();
        c.setId(UUID.fromString("00000000-0000-0000-0000-000000000011"));
        c.setFirstName("Mary");
        c.setLastName("Jane");
        c.setEmail("mary@acme.com");
        c.setPhoneNumber("111-2222");
        c.setCommercialParty(p);

        when(partyRepository.findByPartyId(partyId)).thenReturn(p);
        when(contactRepository.findByCommercialParty(p)).thenReturn(List.of(c));

        GetContactsWithRolesResponse response = service.getContactsWithRoles(partyId);

        assertThat(response.getPartyId()).isEqualTo(partyId.toString());
        assertThat(response.getContacts()).hasSize(1);
        assertThat(response.getContacts().getFirst().getContactName()).isEqualTo("Mary Jane");
        assertThat(response.getContacts().getFirst().getHasPrimaryEmail()).isTrue();
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
    void mergeParties_mergesContactsIdentifiersAndVins() {
        UUID survivorId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID loserId = UUID.fromString("00000000-0000-0000-0000-000000000009");
        CommercialParty survivor = party(survivorId);
        CommercialParty loser = party(loserId);

        Contact loserContact = new Contact();
        loserContact.setId(UUID.fromString("00000000-0000-0000-0000-000000000011"));
        loserContact.setFirstName("Loser");
        loserContact.setLastName("Contact");
        loserContact.setCommercialParty(loser);
        loser.getContacts().add(loserContact);
        loser.getExternalIdentifiers().put("legacy", "L-1");
        loser.getVehicleVins().add("VIN-MERGE");

        when(partyRepository.findByPartyId(survivorId)).thenReturn(survivor);
        when(partyRepository.findByPartyId(loserId)).thenReturn(loser);

        MergePartiesRequest request = new MergePartiesRequest();
        request.setLosingPartyId(loserId.toString());
        request.setJustification("duplicate");

        MergePartiesResponse response = service.mergeParties(survivorId, request);

        assertThat(response.getStatus()).isEqualTo("COMPLETED");
        assertThat(survivor.getContacts()).hasSize(1);
        assertThat(survivor.getExternalIdentifiers()).containsEntry("legacy", "L-1");
        assertThat(survivor.getVehicleVins()).contains("VIN-MERGE");
        assertThat(loser.getStatus()).isEqualTo(AccountStatus.MERGED);
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
    void updateContactRoles_returnsSuccess_whenContactBelongsToParty() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID contactId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        CommercialParty p = party(partyId);
        Contact c = new Contact();
        c.setId(contactId);
        c.setCommercialParty(p);

        when(partyRepository.findByPartyId(partyId)).thenReturn(p);
        when(contactRepository.findById(contactId)).thenReturn(Optional.of(c));

        UpdateContactRolesResponse response =
                service.updateContactRoles(partyId, contactId, new UpdateContactRolesRequest());

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getContactId()).isEqualTo(contactId.toString());
        assertThat(response.getPartyId()).isEqualTo(partyId.toString());
    }

    @Test
    void updateContactRoles_throwsBadRequest_whenContactNotInParty() {
        UUID partyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID contactId = UUID.fromString("00000000-0000-0000-0000-000000000011");
        CommercialParty p1 = party(partyId);
        CommercialParty p2 = party(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        Contact c = new Contact();
        c.setId(contactId);
        c.setCommercialParty(p2);

        when(partyRepository.findByPartyId(partyId)).thenReturn(p1);
        when(contactRepository.findById(contactId)).thenReturn(Optional.of(c));

        UpdateContactRolesRequest request = new UpdateContactRolesRequest();
        assertThatThrownBy(() -> service.updateContactRoles(partyId, contactId, request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400 BAD_REQUEST")
                .hasMessageContaining("contact does not belong to this party");
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

        var result = service.getBillingRulesForParty(partyId);

        assertThat(result).isNull();
    }
}
