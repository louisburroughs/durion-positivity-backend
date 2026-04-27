package com.positivity.customer.internal.service;

import com.positivity.customer.internal.client.PeopleClient;
import com.positivity.customer.internal.client.VehicleInventoryClient;
import com.positivity.customer.internal.config.CacheConfig;
import com.positivity.customer.internal.dto.CreateCommercialAccountRequest;
import com.positivity.customer.internal.dto.CreateCommercialAccountResponse;
import com.positivity.customer.internal.dto.CreateVehicleForPartyRequest;
import com.positivity.customer.internal.dto.CreateVehicleForPartyResponse;
import com.positivity.customer.internal.dto.DuplicateCheckResponse;
import com.positivity.customer.internal.dto.GetCommunicationPreferencesResponse;
import com.positivity.customer.internal.dto.GetContactsWithRolesResponse;
import com.positivity.customer.internal.dto.GetPartyResponse;
import com.positivity.customer.internal.dto.MergePartiesRequest;
import com.positivity.customer.internal.dto.MergePartiesResponse;
import com.positivity.customer.internal.dto.SearchPartiesRequest;
import com.positivity.customer.internal.dto.SearchPartiesResponse;
import com.positivity.customer.internal.dto.UpdateContactRolesRequest;
import com.positivity.customer.internal.dto.UpdateContactRolesResponse;
import com.positivity.customer.internal.dto.UpsertBillingRulesRequest;
import com.positivity.customer.internal.dto.UpsertCommunicationPreferencesRequest;
import com.positivity.customer.internal.dto.UpsertCommunicationPreferencesResponse;
import com.positivity.customer.internal.dto.snapshot.AccountSummary;
import com.positivity.customer.internal.dto.snapshot.BillingRuleRef;
import com.positivity.customer.internal.dto.snapshot.ContactSummary;
import com.positivity.customer.internal.dto.snapshot.CrmSnapshotDTO;
import com.positivity.customer.internal.dto.snapshot.SnapshotMetadata;
import com.positivity.customer.internal.entity.BillingRulesEmbeddable;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.Contact;
import com.positivity.customer.internal.enums.AccountStatus;
import com.positivity.customer.internal.enums.PartyType;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.ContactRepository;
import com.positivity.customer.service.PartyService;
import com.positivity.shared.dto.VehicleResponse;
import com.positivity.shared.id.UUIDv7Generator;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
@RequiredArgsConstructor
public class PartyServiceImpl implements PartyService {
    private static final String SUCCESS = "SUCCESS";
    private static final String NOT_APPLICABLE = "NOT_APPLICABLE";
    private static final String DEFAULT_CONTACT_NAME = "Contact";

    private final Clock clock;

    private final CommercialPartyRepository partyRepository;
    private final ContactRepository contactRepository;
    private final CacheManager cacheManager;
    private final PeopleClient peopleClient;
    private final VehicleInventoryClient vehicleInventoryClient;

    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT.withLocale(Locale.US);

    @Override
    @Transactional
    public CreateCommercialAccountResponse createCommercialAccount(CreateCommercialAccountRequest request) {
        log.debug("Creating commercial account with legalName: {}", request != null ? request.getLegalName() : null);
        if (request == null || !StringUtils.hasText(request.getLegalName())) {
            log.warn("CreateCommercialAccount failed: legalName is required");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "legalName is required");
        }

        CommercialParty party = new CommercialParty();
        party.setLegalName(request.getLegalName());
        party.setDisplayName(request.getDisplayName());
        party.setTaxId(request.getTaxId());
        party.setBillingTermsId(request.getBillingTermsId());
        party.setPartyType(
                StringUtils.hasText(request.getPartyType())
                        ? PartyType.valueOf(request.getPartyType())
                        : PartyType.COMMERCIAL);
        party.setStatus(AccountStatus.ACTIVE);
        party.setPartyNumber(generatePartyNumber());
        if (request.getExternalIdentifiers() != null) {
            party.getExternalIdentifiers().putAll(request.getExternalIdentifiers());
        }

        Contact contact = buildContactForParty(request, party);
        party.getContacts().add(contact);

        CommercialParty saved = partyRepository.save(party);
        contactRepository.save(contact);
        log.info(
                "Created commercial account with partyId: {}, partyNumber: {}",
                saved.getPartyId(),
                saved.getPartyNumber());

        return CreateCommercialAccountResponse.builder()
                .partyId(String.valueOf(saved.getPartyId()))
                .legalName(saved.getLegalName())
                .status(saved.getStatus().toString())
                .createdAt(saved.getCreatedAt())
                .duplicateCandidates(new ArrayList<>())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public GetPartyResponse getParty(UUID partyId) {
        log.debug("Fetching party with id: {}", partyId);
        CommercialParty party = findPartyOrThrow(partyId);
        return GetPartyResponse.builder()
                .partyId(String.valueOf(party.getPartyId()))
                .partyType(party.getPartyType().toString())
                .legalName(party.getLegalName())
                .displayName(party.getDisplayName())
                .taxId(party.getTaxId())
                .status(party.getStatus().toString())
                .billingTermsId(party.getBillingTermsId())
                .createdAt(party.getCreatedAt() != null ? ISO_FORMATTER.format(party.getCreatedAt()) : null)
                .modifiedAt(party.getModifiedAt() != null ? ISO_FORMATTER.format(party.getModifiedAt()) : null)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public GetContactsWithRolesResponse getContactsWithRoles(UUID partyId) {
        log.debug("Fetching contacts with roles for party: {}", partyId);
        CommercialParty party = findPartyOrThrow(partyId);
        List<Contact> contacts = contactRepository.findByCommercialParty(party);

        List<GetContactsWithRolesResponse.ContactWithRoles> contactDtos = new ArrayList<>();
        for (Contact contact : contacts) {
            GetContactsWithRolesResponse.ContactWithRoles dto = GetContactsWithRolesResponse.ContactWithRoles.builder()
                    .contactId(String.valueOf(contact.getContactId()))
                    .contactName(buildContactName(contact))
                    .email(contact.getEmail())
                    .phone(contact.getPhoneNumber())
                    .hasPrimaryEmail(StringUtils.hasText(contact.getEmail()))
                    .roles(new ArrayList<>())
                    .invoiceDeliveryMethod(null)
                    .build();
            contactDtos.add(dto);
        }

        return GetContactsWithRolesResponse.builder()
                .partyId(String.valueOf(party.getPartyId()))
                .contacts(contactDtos)
                .build();
    }

    private CommercialParty findPartyOrThrow(UUID partyId) {
        CommercialParty party = partyRepository.findByPartyId(partyId);
        if (party == null) {
            log.warn("Party not found with id: {}", partyId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "party not found");
        }
        return party;
    }

    private Contact buildContactForParty(CreateCommercialAccountRequest request, CommercialParty party) {
        String firstName = request.getContactFirstName();
        String lastName = request.getContactLastName();

        if (!StringUtils.hasText(lastName)) {
            throw new IllegalArgumentException("contactLastName is required");
        }

        // Fall back to business name if no contact first name provided
        if (!StringUtils.hasText(firstName)) {
            firstName = StringUtils.hasText(request.getDisplayName()) ? request.getDisplayName()
                    : request.getLegalName();
        }

        Contact contact = new Contact();
        UUID personId = peopleClient.resolveOrCreatePersonId(request.getEmail(), request.getPhone(), lastName,
                firstName);
        contact.setCommercialParty(party);
        contact.setPersonId(personId);
        contact.setFirstName(firstName);
        contact.setLastName(lastName);
        contact.setEmail(request.getEmail());
        contact.setPhoneNumber(request.getPhone());
        contact.setActive(true);
        return contact;
    }

    private String generatePartyNumber() {
        return "PARTY-" + UUIDv7Generator.generate().toString().substring(0, 8).toUpperCase(Locale.US);
    }

    private String buildContactName(Contact contact) {
        return (contact.getFirstName() != null ? contact.getFirstName() : "") + " "
                + (contact.getLastName() != null ? contact.getLastName() : "");
    }

    @Override
    @Transactional(readOnly = true)
    public SearchPartiesResponse searchParties(SearchPartiesRequest request) {
        log.debug("Searching parties with criteria: {}", request);
        final SearchPartiesRequest searchRequest = request != null ? request : new SearchPartiesRequest();

        // Simple search implementation - in production would use Specification or
        // QueryDSL
        List<CommercialParty> allParties = partyRepository.findAll();
        List<CommercialParty> filtered = allParties.stream()
                .filter(p -> matchesSearchCriteria(p, searchRequest))
                .toList();

        List<SearchPartiesResponse.PartySummary> summaries = filtered.stream().map(this::mapToPartySummary).toList();

        log.debug("Found {} parties matching search criteria", summaries.size());

        return SearchPartiesResponse.builder()
                .results(summaries)
                .totalCount(summaries.size())
                .pageNumber(0)
                .pageSize(summaries.size())
                .build();
    }

    @Override
    @Transactional
    public MergePartiesResponse mergeParties(UUID survivorPartyId, MergePartiesRequest request) {
        log.info(
                "Merging parties - survivor: {}, loser: {}",
                survivorPartyId,
                request != null ? request.getLosingPartyId() : null);
        if (request == null || !StringUtils.hasText(request.getLosingPartyId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "losingPartyId is required");
        }
        if (!StringUtils.hasText(request.getJustification())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "justification is required");
        }

        CommercialParty survivor = findPartyOrThrow(survivorPartyId);
        UUID losingPartyId;
        try {
            losingPartyId = UUID.fromString(request.getLosingPartyId());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid UUID format");
        }
        CommercialParty loser = findPartyOrThrow(losingPartyId);

        if (survivor.getPartyId().equals(loser.getPartyId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot merge party with itself");
        }

        // Merge contacts from loser to survivor
        for (Contact contact : new ArrayList<>(loser.getContacts())) {
            contact.setCommercialParty(survivor);
            survivor.getContacts().add(contact);
        }
        loser.getContacts().clear();

        // Merge external identifiers
        survivor.getExternalIdentifiers().putAll(loser.getExternalIdentifiers());

        // Merge vehicle VINs
        survivor.getVehicleVins().addAll(loser.getVehicleVins());

        // Mark loser as inactive
        loser.setStatus(AccountStatus.MERGED);

        partyRepository.save(survivor);
        partyRepository.save(loser);
        log.info("Successfully merged parties - survivor: {}, loser: {}", survivor.getPartyId(), loser.getPartyId());

        return MergePartiesResponse.builder()
                .survivorPartyId(String.valueOf(survivor.getPartyId()))
                .losingPartyId(String.valueOf(loser.getPartyId()))
                .status("COMPLETED")
                .build();
    }

    @Override
    @Transactional
    public UpdateContactRolesResponse updateContactRoles(
            UUID partyId, UUID contactId, UpdateContactRolesRequest request) {
        log.debug("Updating contact roles for party: {}, contact: {}", partyId, contactId);
        CommercialParty party = findPartyOrThrow(partyId);

        Contact contact = contactRepository
                .findById(contactId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "contact not found"));

        if (!contact.getCommercialParty().getPartyId().equals(party.getPartyId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "contact does not belong to this party");
        }

        // In a real implementation, would store roles in a separate ContactRole entity
        // For now, return success response
        return UpdateContactRolesResponse.builder()
                .contactId(String.valueOf(contact.getContactId()))
                .partyId(String.valueOf(party.getPartyId()))
                .status(SUCCESS)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public GetCommunicationPreferencesResponse getCommunicationPreferences(UUID partyId) {
        log.debug("Fetching communication preferences for party: {}", partyId);
        CommercialParty party = findPartyOrThrow(partyId);

        // Return default/empty preferences - would fetch from separate entity in
        // production
        return GetCommunicationPreferencesResponse.builder()
                .partyId(String.valueOf(party.getPartyId()))
                .emailPreference(NOT_APPLICABLE)
                .smsPreference(NOT_APPLICABLE)
                .phonePreference(NOT_APPLICABLE)
                .build();
    }

    @Override
    @Transactional
    public UpsertCommunicationPreferencesResponse upsertCommunicationPreferences(
            UUID partyId, UpsertCommunicationPreferencesRequest request) {
        log.debug("Upserting communication preferences for party: {}", partyId);
        CommercialParty party = findPartyOrThrow(partyId);

        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }

        // Would store in separate CommunicationPreference entity in production
        return UpsertCommunicationPreferencesResponse.builder()
                .partyId(String.valueOf(party.getPartyId()))
                .status(SUCCESS)
                .build();
    }

    @Override
    @Transactional
    public CreateVehicleForPartyResponse createVehicleForParty(UUID partyId, CreateVehicleForPartyRequest request) {
        log.debug(
                "Creating vehicle association for party: {}, VIN: {}",
                partyId,
                request != null ? request.getVinNumber() : null);
        if (request == null || !StringUtils.hasText(request.getVinNumber())) {
            log.warn("CreateVehicleForParty failed: vinNumber is required");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "vinNumber is required");
        }

        CommercialParty party = findPartyOrThrow(partyId);

        if (party.getVehicleVins().contains(request.getVinNumber())) {
            log.warn("Vehicle VIN {} already associated with party {}", request.getVinNumber(), partyId);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Vehicle already associated with this party");
        }

        party.getVehicleVins().add(request.getVinNumber());
        partyRepository.save(party);
        log.info("Associated vehicle VIN {} with party {}", request.getVinNumber(), partyId);

        return CreateVehicleForPartyResponse.builder()
                .partyId(String.valueOf(party.getPartyId()))
                .vinNumber(request.getVinNumber())
                .status(SUCCESS)
                .build();
    }

    private boolean matchesSearchCriteria(CommercialParty party, SearchPartiesRequest request) {
        return matchesNameFilter(party, request.getName())
                && matchesTaxIdFilter(party, request.getTaxId())
                && matchesPartyTypeFilter(party, request.getPartyType())
                && matchesStatusFilter(party, request.getStatus());
    }

    private boolean matchesNameFilter(CommercialParty party, String requestedName) {
        if (!StringUtils.hasText(requestedName)) {
            return true;
        }

        String searchTerm = requestedName.toLowerCase(Locale.US);
        return containsIgnoreCase(party.getLegalName(), searchTerm)
                || containsIgnoreCase(party.getDisplayName(), searchTerm);
    }

    private boolean matchesTaxIdFilter(CommercialParty party, String requestedTaxId) {
        return !StringUtils.hasText(requestedTaxId) || requestedTaxId.equals(party.getTaxId());
    }

    private boolean matchesPartyTypeFilter(CommercialParty party, String requestedPartyType) {
        return matchesEnumFilter(requestedPartyType, PartyType.class, party.getPartyType());
    }

    private boolean matchesStatusFilter(CommercialParty party, String requestedStatus) {
        return matchesEnumFilter(requestedStatus, AccountStatus.class, party.getStatus());
    }

    private <E extends Enum<E>> boolean matchesEnumFilter(String rawFilter, Class<E> enumType, E actualValue) {
        if (!StringUtils.hasText(rawFilter)) {
            return true;
        }

        E parsedFilter = parseEnumFilter(rawFilter, enumType);
        return parsedFilter != null && parsedFilter == actualValue;
    }

    private boolean containsIgnoreCase(String value, String lowerCaseSearchTerm) {
        return StringUtils.hasText(value) && value.toLowerCase(Locale.US).contains(lowerCaseSearchTerm);
    }

    private <E extends Enum<E>> E parseEnumFilter(String rawValue, Class<E> enumType) {
        try {
            return Enum.valueOf(enumType, rawValue.trim().toUpperCase(Locale.US));
        } catch (IllegalArgumentException ex) {
            log.debug("Ignoring invalid {} filter value '{}'", enumType.getSimpleName(), rawValue);
            return null;
        }
    }

    private SearchPartiesResponse.PartySummary mapToPartySummary(CommercialParty party) {
        return SearchPartiesResponse.PartySummary.builder()
                .partyId(String.valueOf(party.getPartyId()))
                .legalName(party.getLegalName())
                .displayName(party.getDisplayName())
                .partyType(party.getPartyType().toString())
                .status(party.getStatus().toString())
                .createdAt(party.getCreatedAt() != null ? ISO_FORMATTER.format(party.getCreatedAt()) : null)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public CommercialParty findPartyById(UUID partyId) {
        return findPartyByIdInternal(partyId);
    }

    private CommercialParty findPartyByIdInternal(UUID partyId) {
        log.debug("Finding party by ID: {}", partyId);
        return partyRepository.findByPartyId(partyId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Contact> findContactsByParty(CommercialParty party) {
        return findContactsByPartyInternal(party);
    }

    private List<Contact> findContactsByPartyInternal(CommercialParty party) {
        log.debug("Finding contacts for party: {}", party.getPartyId());
        return contactRepository.findByCommercialParty(party);
    }

    @Override
    @Transactional(readOnly = true)
    public CrmSnapshotDTO buildSnapshotForParty(UUID partyId) {
        log.debug("Building CRM snapshot for party: {}", partyId);
        Cache snapshotCache = cacheManager.getCache(CacheConfig.SNAPSHOT_CACHE);
        if (snapshotCache != null) {
            Cache.ValueWrapper wrapper = snapshotCache.get(partyId);
            if (wrapper != null) {
                CrmSnapshotDTO cached = (CrmSnapshotDTO) wrapper.get();
                if (cached != null && cached.getSnapshotMetadata() != null) {
                    cached.getSnapshotMetadata().setSource("CACHE");
                }
                if (cached != null) {
                    return cached;
                }
            }
        }

        CommercialParty party = findPartyByIdInternal(partyId);

        if (party == null) {
            return null;
        }

        CrmSnapshotDTO fresh = assembleSnapshot(party);
        if (fresh.getSnapshotMetadata() != null) {
            fresh.getSnapshotMetadata().setSource("CRM_API");
        }
        if (snapshotCache != null) {
            snapshotCache.put(partyId, fresh);
        }
        return fresh;
    }

    @Override
    @Transactional(readOnly = true)
    public BillingRuleRef getBillingRulesForParty(@NonNull UUID partyId) {
        log.debug("Fetching billing rules for party: {}", partyId);
        CommercialParty party = findPartyByIdInternal(partyId);
        if (party == null) {
            log.warn("Party not found when fetching billing rules: {}", partyId);
            return null;
        }
        BillingRulesEmbeddable embedded = party.getBillingRules();
        if (embedded == null) {
            return BillingRuleRef.defaults();
        }
        return mapToBillingRuleRef(embedded);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull DuplicateCheckResponse checkPartyDuplicates(@NonNull String legalName) {
        String requestedLegalName = legalName.trim();
        if (!StringUtils.hasText(requestedLegalName)) {
            return DuplicateCheckResponse.builder()
                    .duplicatesFound(false)
                    .exactMatchPartyId(null)
                    .potentialDuplicates(Collections.emptyList())
                    .build();
        }

        List<CommercialParty> matches = partyRepository.findByLegalNameContaining(requestedLegalName);
        String exactMatchPartyId = matches.stream()
                .filter(match -> requestedLegalName.equalsIgnoreCase(match.getLegalName()))
                .map(match -> match.getPartyId().toString())
                .findFirst()
                .orElse(null);

        List<DuplicateCheckResponse.PartyMatch> potentialDuplicates = matches.stream()
                .map(match -> {
                    boolean exactMatch = requestedLegalName.equalsIgnoreCase(match.getLegalName());
                    return DuplicateCheckResponse.PartyMatch.builder()
                            .partyId(match.getPartyId().toString())
                            .legalName(match.getLegalName())
                            .matchType(exactMatch ? "EXACT" : "FUZZY")
                            .score(exactMatch ? 1.0 : 0.7)
                            .build();
                })
                .toList();

        return DuplicateCheckResponse.builder()
                .duplicatesFound(!matches.isEmpty())
                .exactMatchPartyId(exactMatchPartyId)
                .potentialDuplicates(potentialDuplicates)
                .build();
    }

    @Override
    @Transactional
    public @NonNull BillingRuleRef upsertBillingRulesForParty(
            @NonNull UUID partyId, @NonNull UpsertBillingRulesRequest request) {
        log.info("Upserting billing rules for partyId={}", partyId);
        CommercialParty party = findPartyByIdInternal(partyId);
        if (party == null) {
            throw new IllegalArgumentException("Party not found: " + partyId);
        }

        BillingRulesEmbeddable embedded = new BillingRulesEmbeddable();
        embedded.setPoRequired(request.isPoRequired());
        embedded.setTaxExempt(request.isTaxExempt());
        embedded.setCreditHold(request.isCreditHold());
        embedded.setAutoPayEnabled(request.isAutoPayEnabled());
        embedded.setPaymentTerms(request.getPaymentTerms());
        embedded.setCreditLimit(request.getCreditLimit());
        embedded.setCurrency(request.getCurrency());
        embedded.setInvoiceDeliveryMethod(request.getInvoiceDeliveryMethod());
        embedded.setBillingAddressId(request.getBillingAddressId());
        embedded.setDiscountPolicyRef(request.getDiscountPolicyRef());
        party.setBillingRules(embedded);
        partyRepository.save(party);
        return mapToBillingRuleRef(embedded);
    }

    private BillingRuleRef mapToBillingRuleRef(BillingRulesEmbeddable embedded) {
        BillingRuleRef ref = new BillingRuleRef();
        ref.setPoRequired(embedded.isPoRequired());
        ref.setTaxExempt(embedded.isTaxExempt());
        ref.setCreditHold(embedded.isCreditHold());
        ref.setAutoPayEnabled(embedded.isAutoPayEnabled());
        ref.setPaymentTerms(embedded.getPaymentTerms());
        ref.setCreditLimit(embedded.getCreditLimit());
        ref.setCurrency(embedded.getCurrency());
        ref.setInvoiceDeliveryMethod(embedded.getInvoiceDeliveryMethod());
        ref.setBillingAddressId(embedded.getBillingAddressId());
        ref.setDiscountPolicyRef(embedded.getDiscountPolicyRef());
        return ref;
    }

    private CrmSnapshotDTO assembleSnapshot(CommercialParty party) {
        SnapshotMetadata meta = createMetadata();
        AccountSummary acct = buildAccountSummary(party);
        List<ContactSummary> contacts = buildContactSummaries(party);
        List<CrmSnapshotDTO.VehicleSummary> vehicles = buildVehicleSummaries(party);
        CrmSnapshotDTO.BillingPreferences prefs = buildBillingPreferences();

        return new CrmSnapshotDTO(meta, acct, contacts, vehicles, prefs, BillingRuleRef.defaults());
    }

    private SnapshotMetadata createMetadata() {
        SnapshotMetadata meta = new SnapshotMetadata(UUIDv7Generator.generate(), Instant.now(clock), "1.0.0");
        meta.setSource("CRM_API");
        return meta;
    }

    private AccountSummary buildAccountSummary(CommercialParty party) {
        String partyNumber = requireText(party.getPartyNumber(), "partyNumber");
        String legalName = requireText(party.getLegalName(), "legalName");
        PartyType partyType = requireNonNullField(party.getPartyType(), "partyType");
        String id = party.getPartyId().toString();
        String name = StringUtils.hasText(party.getDisplayName()) ? party.getDisplayName() : legalName;
        String type = partyType.name();

        return new AccountSummary(id, partyNumber, name, type);
    }

    private String requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("Commercial party missing required field: " + fieldName);
        }
        return value;
    }

    private <T> T requireNonNullField(T value, String fieldName) {
        if (value == null) {
            throw new IllegalStateException("Commercial party missing required field: " + fieldName);
        }
        return value;
    }

    private List<ContactSummary> buildContactSummaries(CommercialParty party) {
        List<Contact> contacts = findContactsByPartyInternal(party);

        if (contacts == null || contacts.isEmpty()) {
            return Collections.emptyList();
        }

        return contacts.stream().map(this::convertContactToSummary).toList();
    }

    private ContactSummary convertContactToSummary(Contact contact) {
        ContactSummary summary = new ContactSummary();
        summary.setContactId(contact.getContactId().toString());
        summary.setPrimary(false);
        summary.setName(formatContactName(contact));
        summary.setRoles(Collections.emptyList());
        summary.setPhoneNumbers(buildPhoneList(contact));
        summary.setEmailAddresses(buildEmailList(contact));
        summary.setPreferences(null);
        return summary;
    }

    private String formatContactName(Contact contact) {
        if (contact == null) {
            return DEFAULT_CONTACT_NAME;
        }

        StringBuilder nameBuilder = new StringBuilder();

        if (StringUtils.hasText(contact.getFirstName())) {
            nameBuilder.append(contact.getFirstName().trim());
        }

        if (StringUtils.hasText(contact.getLastName())) {
            if (!nameBuilder.isEmpty()) {
                nameBuilder.append(" ");
            }
            nameBuilder.append(contact.getLastName().trim());
        }

        if (!nameBuilder.isEmpty()) {
            return nameBuilder.toString();
        }

        if (StringUtils.hasText(contact.getEmail())) {
            return contact.getEmail().trim();
        }

        if (StringUtils.hasText(contact.getPhoneNumber())) {
            return contact.getPhoneNumber().trim();
        }

        if (contact.getPersonId() != null) {
            return contact.getPersonId().toString();
        }

        if (contact.getContactId() != null) {
            return contact.getContactId().toString();
        }

        return DEFAULT_CONTACT_NAME;
    }

    private List<ContactSummary.PhoneNumberDTO> buildPhoneList(Contact contact) {
        List<ContactSummary.PhoneNumberDTO> phones = new ArrayList<>();

        if (contact.getPhoneNumber() != null && !contact.getPhoneNumber().isBlank()) {
            phones.add(new ContactSummary.PhoneNumberDTO("PRIMARY", contact.getPhoneNumber()));
        }

        return phones;
    }

    private List<ContactSummary.EmailAddressDTO> buildEmailList(Contact contact) {
        List<ContactSummary.EmailAddressDTO> emails = new ArrayList<>();

        if (contact.getEmail() != null && !contact.getEmail().isBlank()) {
            emails.add(new ContactSummary.EmailAddressDTO("PRIMARY", contact.getEmail()));
        }

        return emails;
    }

    private List<CrmSnapshotDTO.VehicleSummary> buildVehicleSummaries(CommercialParty party) {
        if (party == null
                || party.getVehicleVins() == null
                || party.getVehicleVins().isEmpty()) {
            return Collections.emptyList();
        }

        return party.getVehicleVins().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .map(this::fetchVehicleSummaryByVin)
                .filter(Objects::nonNull)
                .toList();
    }

    private CrmSnapshotDTO.VehicleSummary fetchVehicleSummaryByVin(String vinCode) {
        try {
            VehicleResponse vehicleData = vehicleInventoryClient.getVehicleByVin(vinCode).orElse(null);
            if (vehicleData == null) {
                log.debug("Vehicle lookup by VIN returned null: {}", vinCode);
                return null;
            }

            CrmSnapshotDTO.VehicleSummary summary = new CrmSnapshotDTO.VehicleSummary();
            String vehicleId = vehicleData.getVehicleId() != null
                    ? vehicleData.getVehicleId().toString()
                    : vinCode;
            summary.setVehicleId(vehicleId);
            summary.setVin(vehicleData.getVin() != null ? vehicleData.getVin() : vinCode);
            summary.setLicensePlate(vehicleData.getLicensePlate());
            summary.setMake(vehicleData.getMake());
            summary.setModel(vehicleData.getModel());
            summary.setYear(vehicleData.getYear());
            return summary;
        } catch (Exception ex) {
            log.warn("Vehicle fetch failed for VIN {}: {}", vinCode, ex.getMessage());
            return null;
        }
    }

    private CrmSnapshotDTO.BillingPreferences buildBillingPreferences() {
        CrmSnapshotDTO.BillingPreferences prefs = new CrmSnapshotDTO.BillingPreferences();
        prefs.setMarketingOptOut(false);
        prefs.setDoNotContact(false);
        prefs.setInvoiceDeliveryMethod(null);
        return prefs;
    }
}
