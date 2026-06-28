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
import com.positivity.customer.internal.dto.GetPartyResponse;
import com.positivity.customer.internal.dto.MergePartiesRequest;
import com.positivity.customer.internal.dto.MergePartiesResponse;
import com.positivity.customer.internal.dto.SearchPartiesRequest;
import com.positivity.customer.internal.dto.SearchPartiesResponse;
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
import com.positivity.customer.internal.entity.Party;
import com.positivity.customer.internal.entity.PartyRelationship;
import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.enums.AccountStatus;
import com.positivity.customer.internal.enums.PartyType;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.PartyRelationshipRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.customer.service.PartyService;
import com.positivity.shared.dto.VehicleResponse;
import com.positivity.shared.id.UUIDv7Generator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

    private final Clock clock;

    private final CommercialPartyRepository partyRepository;
    private final PersonPartyRepository personPartyRepository;
    private final PartyRelationshipRepository partyRelationshipRepository;
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
        party.setCustomerNumber(generateCustomerNumber());
        if (request.getExternalIdentifiers() != null) {
            party.getExternalIdentifiers().putAll(request.getExternalIdentifiers());
        }

        CommercialParty saved = partyRepository.save(party);
        log.info(
                "Created commercial account with partyId: {}, customerNumber: {}",
                saved.getPartyId(),
                saved.getCustomerNumber());

        return CreateCommercialAccountResponse.builder()
                .partyId(String.valueOf(saved.getPartyId()))
                .legalName(saved.getLegalName())
                .status(saved.getStatus().toString())
                .customerNumber(saved.getCustomerNumber())
                .displayName(saved.getDisplayName())
                .partyType(saved.getPartyType() != null ? saved.getPartyType().toString() : null)
                .taxId(saved.getTaxId())
                .billingTermsId(saved.getBillingTermsId())
                .primaryAddress(saved.getPrimaryAddress())
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

    private CommercialParty findPartyOrThrow(UUID partyId) {
        CommercialParty party = partyRepository.findByPartyId(partyId);
        if (party == null) {
            log.warn("Party not found with id: {}", partyId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "party not found");
        }
        return party;
    }

    private String generateCustomerNumber() {
        return "CUST-" + UUIDv7Generator.generate().toString().substring(0, 8).toUpperCase(Locale.US);
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public SearchPartiesResponse browseParties(@NonNull Pageable pageable) {
        return browseParties(pageable, null, null, null, null, null, null);
    }

    @Override
    @Transactional(readOnly = true)
    public SearchPartiesResponse browseParties(
            @NonNull Pageable pageable,
            String name,
            String status,
            String partyType,
            String customerNumber,
            String sortField,
            String sortOrder) {
        Pageable normalizedPageable = normalizeBrowsePageable(pageable);

        // Unified customer directory: commercial parties plus standalone individual
        // customers (person parties that are not commercial-account contacts).
        // Merged, filtered, sorted, and paged in-memory; customer volumes are well
        // within a single window at this tier (see ADR-0026 / OQ3 in PLAN-person-unification).
        List<SearchPartiesResponse.PartySummary> all = new ArrayList<>();
        partyRepository.findAll().stream().map(this::mapToPartySummary).forEach(all::add);

        List<PersonParty> individuals = personPartyRepository.findIndividualCustomers();
        Map<UUID, PeopleClient.PersonIdentity> identities = fetchIdentitiesFor(individuals);
        individuals.stream()
                .map(person -> mapPersonToPartySummary(person, identities))
                .forEach(all::add);

        List<SearchPartiesResponse.PartySummary> filtered = all.stream()
                .filter(s -> matchesBrowseFilter(s, name, status, partyType, customerNumber))
                .sorted(browseComparator(sortField, sortOrder))
                .toList();

        int total = filtered.size();
        int from = Math.min(normalizedPageable.getPageNumber() * normalizedPageable.getPageSize(), total);
        int to = Math.min(from + normalizedPageable.getPageSize(), total);
        List<SearchPartiesResponse.PartySummary> window = filtered.subList(from, to);

        return SearchPartiesResponse.builder()
                .results(new ArrayList<>(window))
                .totalCount(safeTotalCount(total))
                .pageNumber(normalizedPageable.getPageNumber())
                .pageSize(normalizedPageable.getPageSize())
                .build();
    }

    private boolean matchesBrowseFilter(
            SearchPartiesResponse.PartySummary s, String name, String status, String partyType, String customerNumber) {
        if (StringUtils.hasText(name)) {
            // Unified typeahead term: match legal name, display name, OR customer number
            // so a single query box finds customers by name or by account number.
            String needle = name.toLowerCase(java.util.Locale.ROOT);
            String legal = s.getLegalName() != null ? s.getLegalName().toLowerCase(java.util.Locale.ROOT) : "";
            String display = s.getDisplayName() != null ? s.getDisplayName().toLowerCase(java.util.Locale.ROOT) : "";
            String number =
                    s.getCustomerNumber() != null ? s.getCustomerNumber().toLowerCase(java.util.Locale.ROOT) : "";
            if (!legal.contains(needle) && !display.contains(needle) && !number.contains(needle)) {
                return false;
            }
        }
        if (StringUtils.hasText(status) && !status.equalsIgnoreCase(s.getStatus())) {
            return false;
        }
        if (StringUtils.hasText(partyType) && !partyType.equalsIgnoreCase(s.getPartyType())) {
            return false;
        }
        if (StringUtils.hasText(customerNumber)) {
            String cn = s.getCustomerNumber() != null ? s.getCustomerNumber().toLowerCase(java.util.Locale.ROOT) : "";
            return cn.contains(customerNumber.toLowerCase(java.util.Locale.ROOT));
        }
        return true;
    }

    private Comparator<SearchPartiesResponse.PartySummary> browseComparator(String sortField, String sortOrder) {
        Comparator<SearchPartiesResponse.PartySummary> base;
        if ("customerNumber".equalsIgnoreCase(sortField)) {
            base = Comparator.comparing(
                    SearchPartiesResponse.PartySummary::getCustomerNumber,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
        } else {
            base = Comparator.comparing(
                    (SearchPartiesResponse.PartySummary s) ->
                            s.getDisplayName() != null ? s.getDisplayName() : s.getLegalName(),
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
        }
        if ("desc".equalsIgnoreCase(sortOrder)) {
            base = base.reversed();
        }
        // Stable tie-breaker so paging is deterministic.
        return base.thenComparing(SearchPartiesResponse.PartySummary::getPartyId);
    }

    private SearchPartiesResponse.PartySummary mapPersonToPartySummary(
            PersonParty person, Map<UUID, PeopleClient.PersonIdentity> identities) {
        String displayName = resolveDisplayName(person, identities);
        return SearchPartiesResponse.PartySummary.builder()
                .partyId(String.valueOf(person.getPartyId()))
                .legalName(displayName)
                .displayName(displayName)
                .partyType(person.getPartyType().toString())
                .customerNumber(person.getCustomerNumber())
                .status(person.getStatus() != null ? person.getStatus().toString() : null)
                .createdAt(person.getCreatedAt() != null ? ISO_FORMATTER.format(person.getCreatedAt()) : null)
                .primaryContact(resolvePersonPrimaryContact(person, displayName, identities))
                .vehicleCount(vehicleCount(person))
                .build();
    }

    /**
     * For a standalone individual customer the party's primary contact is the person
     * themselves: name plus their first email/phone (pos-people source of truth).
     */
    private SearchPartiesResponse.PrimaryContact resolvePersonPrimaryContact(
            PersonParty person, String displayName, Map<UUID, PeopleClient.PersonIdentity> identities) {
        PeopleClient.PersonIdentity identity =
                person.getPersonId() != null ? identities.get(person.getPersonId()) : null;
        List<String> emails = resolveEmails(person, identity);
        List<String> phones = resolvePhones(person, identity);
        return SearchPartiesResponse.PrimaryContact.builder()
                .name(displayName)
                .email(emails.isEmpty() ? null : emails.get(0))
                .phone(phones.isEmpty() ? null : phones.get(0))
                .build();
    }

    /**
     * Batch-load canonical identities from pos-people (source of truth, ADR-0015 I2)
     * for the given person parties, keyed by the canonical person id. Uses the resilient
     * (quiet) variant so a pos-people outage degrades names/contacts to null on these
     * display read paths (directory browse, contact summaries) rather than failing the
     * whole request — there is no local fallback after issue #684.
     */
    private Map<UUID, PeopleClient.PersonIdentity> fetchIdentitiesFor(Collection<PersonParty> persons) {
        Set<UUID> personIds = persons.stream()
                .map(PersonParty::getPersonId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return peopleClient.fetchPersonIdentitiesQuietly(personIds);
    }

    /**
     * Resolve a person's display name from pos-people (sole source of truth, ADR-0015 I2;
     * issue #684). Returns {@code null} when pos-people has no name for the canonical id
     * (or is unreachable) — there is no local fallback copy.
     */
    private String resolveDisplayName(PersonParty person, Map<UUID, PeopleClient.PersonIdentity> identities) {
        PeopleClient.PersonIdentity identity =
                (identities != null && person.getPersonId() != null) ? identities.get(person.getPersonId()) : null;
        return identity != null && !identity.displayName().isBlank() ? identity.displayName() : null;
    }

    /** Phone values from pos-people (sole source of truth, ADR-0015 I2; issue #684). */
    private List<String> resolvePhones(PersonParty person, PeopleClient.PersonIdentity identity) {
        return identity != null ? identity.phones() : List.of();
    }

    /** Email values from pos-people (sole source of truth, ADR-0015 I2; issue #684). */
    private List<String> resolveEmails(PersonParty person, PeopleClient.PersonIdentity identity) {
        return identity != null ? identity.emails() : List.of();
    }

    private int safeTotalCount(long totalElements) {
        // Response schema exposes int32; cap large totals to avoid overflow exceptions.
        return totalElements > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) totalElements;
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

        List<SearchPartiesResponse.PartySummary> summaries =
                filtered.stream().map(this::mapToPartySummary).toList();

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
        } catch (IllegalArgumentException _) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid UUID format");
        }
        CommercialParty loser = findPartyOrThrow(losingPartyId);

        if (survivor.getPartyId().equals(loser.getPartyId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot merge party with itself");
        }

        // Transfer party relationships from loser to survivor
        partyRelationshipRepository.reassignFromParty(loser.getPartyId(), survivor.getPartyId());

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
        } catch (IllegalArgumentException _) {
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
                .customerNumber(party.getCustomerNumber())
                .status(party.getStatus().toString())
                .createdAt(party.getCreatedAt() != null ? ISO_FORMATTER.format(party.getCreatedAt()) : null)
                .primaryContact(resolvePrimaryContact(party))
                .vehicleCount(vehicleCount(party))
                .build();
    }

    /** Vehicle count for a party; 0 when none. */
    private int vehicleCount(Party party) {
        return party.getVehicleVins() != null ? party.getVehicleVins().size() : 0;
    }

    /**
     * Resolve the primary contact for a commercial party from its active
     * relationships: the contact flagged primary, else the first active contact.
     * Returns null when the party has no active contacts.
     */
    private SearchPartiesResponse.PrimaryContact resolvePrimaryContact(CommercialParty party) {
        List<ContactSummary> contacts = buildContactSummaries(party);
        if (contacts.isEmpty()) {
            return null;
        }
        ContactSummary primary =
                contacts.stream().filter(ContactSummary::isPrimary).findFirst().orElse(contacts.get(0));
        return SearchPartiesResponse.PrimaryContact.builder()
                .name(primary.getName())
                .email(firstContactValue(primary.getEmailAddresses(), ContactSummary.EmailAddressDTO::getAddress))
                .phone(firstContactValue(primary.getPhoneNumbers(), ContactSummary.PhoneNumberDTO::getNumber))
                .build();
    }

    private <T> String firstContactValue(List<T> values, java.util.function.Function<T, String> extractor) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return extractor.apply(values.get(0));
    }

    private Pageable normalizeBrowsePageable(@Nullable Pageable pageable) {
        Sort normalizedSort = normalizeBrowseSort(pageable != null ? pageable.getSort() : Sort.unsorted());
        if (pageable == null || pageable.isUnpaged()) {
            return PageRequest.of(0, 20, normalizedSort);
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), normalizedSort);
    }

    private Sort normalizeBrowseSort(@NonNull Sort requestedSort) {
        if (requestedSort.isUnsorted()) {
            return Sort.by(Sort.Order.asc("legalName").ignoreCase(), Sort.Order.asc("partyId"));
        }

        List<Sort.Order> orders = new ArrayList<>();
        requestedSort.forEach(order -> {
            if ("legalName".equals(order.getProperty())) {
                orders.add(order.ignoreCase());
            } else {
                orders.add(order);
            }
        });
        boolean hasPartyIdSort = orders.stream().anyMatch(order -> "partyId".equals(order.getProperty()));
        if (!hasPartyIdSort) {
            orders.add(Sort.Order.asc("partyId"));
        }
        return Sort.by(orders);
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
    public @Nullable BillingRuleRef getBillingRulesForParty(@NonNull UUID partyId) {
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
        if (requestedLegalName.length() < 2) {
            throw new IllegalArgumentException("legalName must contain at least 2 non-whitespace characters");
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
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Party not found: " + partyId);
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
        BillingRuleRef ref = BillingRuleRef.defaults();
        if (embedded.getPoRequired() != null) {
            ref.setPoRequired(embedded.getPoRequired());
        }
        if (embedded.getTaxExempt() != null) {
            ref.setTaxExempt(embedded.getTaxExempt());
        }
        if (embedded.getCreditHold() != null) {
            ref.setCreditHold(embedded.getCreditHold());
        }
        if (embedded.getAutoPayEnabled() != null) {
            ref.setAutoPayEnabled(embedded.getAutoPayEnabled());
        }
        if (embedded.getPaymentTerms() != null) {
            ref.setPaymentTerms(embedded.getPaymentTerms());
        }
        if (embedded.getCreditLimit() != null) {
            ref.setCreditLimit(embedded.getCreditLimit());
        }
        if (embedded.getCurrency() != null) {
            ref.setCurrency(embedded.getCurrency());
        }
        if (embedded.getInvoiceDeliveryMethod() != null) {
            ref.setInvoiceDeliveryMethod(embedded.getInvoiceDeliveryMethod());
        }
        if (embedded.getBillingAddressId() != null) {
            ref.setBillingAddressId(embedded.getBillingAddressId());
        }
        if (embedded.getDiscountPolicyRef() != null) {
            ref.setDiscountPolicyRef(embedded.getDiscountPolicyRef());
        }
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
        String customerNumber = requireText(party.getCustomerNumber(), "customerNumber");
        String legalName = requireText(party.getLegalName(), "legalName");
        PartyType partyType = requireNonNullField(party.getPartyType(), "partyType");
        String id = party.getPartyId().toString();
        String name = StringUtils.hasText(party.getDisplayName()) ? party.getDisplayName() : legalName;
        String type = partyType.name();

        return new AccountSummary(id, customerNumber, name, type);
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
        LocalDate today = LocalDate.now(clock);
        List<PartyRelationship> relationships =
                partyRelationshipRepository.findActiveByFromPartyId(party.getPartyId(), today);
        Map<UUID, PeopleClient.PersonIdentity> identities = fetchIdentitiesFor(
                relationships.stream().map(PartyRelationship::getToPerson).toList());
        return relationships.stream()
                .map(rel -> convertRelationshipToSummary(rel, identities))
                .toList();
    }

    private ContactSummary convertRelationshipToSummary(
            PartyRelationship rel, Map<UUID, PeopleClient.PersonIdentity> identities) {
        PersonParty person = rel.getToPerson();
        ContactSummary summary = new ContactSummary();
        summary.setContactId(rel.getPartyRelationshipId().toString());
        summary.setPrimary(rel.isPrimaryBillingContact());
        summary.setName(resolveDisplayName(person, identities));
        summary.setRoles(Collections.emptyList());

        PeopleClient.PersonIdentity identity =
                person.getPersonId() != null ? identities.get(person.getPersonId()) : null;
        summary.setPhoneNumbers(resolvePhones(person, identity).stream()
                .map(v -> new ContactSummary.PhoneNumberDTO("PRIMARY", v))
                .toList());
        summary.setEmailAddresses(resolveEmails(person, identity).stream()
                .map(v -> new ContactSummary.EmailAddressDTO("PRIMARY", v))
                .toList());
        summary.setPreferences(null);
        return summary;
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
            VehicleResponse vehicleData =
                    vehicleInventoryClient.getVehicleByVin(vinCode).orElse(null);
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
