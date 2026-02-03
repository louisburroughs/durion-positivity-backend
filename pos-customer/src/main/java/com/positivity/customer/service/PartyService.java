package com.positivity.customer.service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import com.positivity.customer.internal.dto.CreateCommercialAccountRequest;
import com.positivity.customer.internal.dto.CreateCommercialAccountResponse;
import com.positivity.customer.internal.dto.CreateVehicleForPartyRequest;
import com.positivity.customer.internal.dto.CreateVehicleForPartyResponse;
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
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.Contact;
import com.positivity.customer.internal.enums.AccountStatus;
import com.positivity.customer.internal.enums.PartyType;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.ContactRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PartyService {

    private final CommercialPartyRepository partyRepository;
    private final ContactRepository contactRepository;

    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT.withLocale(Locale.US);

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
        party.setPartyType(StringUtils.hasText(request.getPartyType()) ? PartyType.valueOf(request.getPartyType())
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
        log.info("Created commercial account with partyId: {}, partyNumber: {}", saved.getPartyId(),
                saved.getPartyNumber());

        return CreateCommercialAccountResponse.builder()
                .partyId(String.valueOf(saved.getPartyId()))
                .legalName(saved.getLegalName())
                .status(saved.getStatus().toString())
                .createdAt(saved.getCreatedAt())
                .duplicateCandidates(new ArrayList<>())
                .build();
    }

    @Transactional(readOnly = true)
    public GetPartyResponse getParty(String partyId) {
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

    @Transactional(readOnly = true)
    public GetContactsWithRolesResponse getContactsWithRoles(String partyId) {
        log.debug("Fetching contacts with roles for party: {}", partyId);
        CommercialParty party = findPartyOrThrow(partyId);
        List<Contact> contacts = contactRepository.findByCommercialParty(party);

        List<GetContactsWithRolesResponse.ContactWithRoles> contactDtos = new ArrayList<>();
        for (Contact contact : contacts) {
            GetContactsWithRolesResponse.ContactWithRoles dto = GetContactsWithRolesResponse.ContactWithRoles.builder()
                    .contactId(String.valueOf(contact.getId()))
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

    private CommercialParty findPartyOrThrow(String partyId) {
        UUID id = parseUuid(partyId);
        CommercialParty party = partyRepository.findByPartyId(id);
        if (party == null) {
            log.warn("Party not found with id: {}", partyId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "party not found");
        }
        return party;
    }

    private UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            log.warn("Invalid UUID format: {}", raw);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid UUID format");
        }
    }

    private Contact buildContactForParty(CreateCommercialAccountRequest request, CommercialParty party) {
        String nameFallback = request.getDisplayName();
        if (!StringUtils.hasText(nameFallback)) {
            nameFallback = request.getLegalName();
        }
        Contact contact = new Contact();
        contact.setCommercialParty(party);
        contact.setFirstName(nameFallback);
        contact.setLastName("Contact");
        contact.setEmail(request.getEmail());
        contact.setPhoneNumber(request.getPhone());
        contact.setActive(true);
        return contact;
    }

    private String generatePartyNumber() {
        return "PARTY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.US);
    }

    private String buildContactName(Contact contact) {
        return (contact.getFirstName() != null ? contact.getFirstName() : "") + " " +
                (contact.getLastName() != null ? contact.getLastName() : "");
    }

    @Transactional(readOnly = true)
    public SearchPartiesResponse searchParties(SearchPartiesRequest request) {
        log.debug("Searching parties with criteria: {}", request);
        final SearchPartiesRequest searchRequest = request != null ? request : new SearchPartiesRequest();

        // Simple search implementation - in production would use Specification or
        // QueryDSL
        List<CommercialParty> allParties = partyRepository.findAll();
        List<CommercialParty> filtered = allParties.stream()
                .filter(p -> matchesSearchCriteria(p, searchRequest))
                .collect(Collectors.toList());

        List<SearchPartiesResponse.PartySummary> summaries = filtered.stream()
                .map(this::mapToPartySummary)
                .collect(Collectors.toList());

        log.debug("Found {} parties matching search criteria", summaries.size());

        return SearchPartiesResponse.builder()
                .results(summaries)
                .totalCount(summaries.size())
                .pageNumber(0)
                .pageSize(summaries.size())
                .build();
    }

    @Transactional
    public MergePartiesResponse mergeParties(String survivorPartyId, MergePartiesRequest request) {
        log.info("Merging parties - survivor: {}, loser: {}", survivorPartyId,
                request != null ? request.getLosingPartyId() : null);
        if (request == null || !StringUtils.hasText(request.getLosingPartyId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "losingPartyId is required");
        }
        if (!StringUtils.hasText(request.getJustification())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "justification is required");
        }

        CommercialParty survivor = findPartyOrThrow(survivorPartyId);
        CommercialParty loser = findPartyOrThrow(request.getLosingPartyId());

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

    @Transactional
    public UpdateContactRolesResponse updateContactRoles(String partyId, String contactId,
            UpdateContactRolesRequest request) {
        log.debug("Updating contact roles for party: {}, contact: {}", partyId, contactId);
        CommercialParty party = findPartyOrThrow(partyId);
        UUID contactIdUuid = parseUuid(contactId);

        Contact contact = contactRepository.findById(contactIdUuid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "contact not found"));

        if (!contact.getCommercialParty().getPartyId().equals(party.getPartyId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "contact does not belong to this party");
        }

        // In a real implementation, would store roles in a separate ContactRole entity
        // For now, return success response
        return UpdateContactRolesResponse.builder()
                .contactId(String.valueOf(contact.getId()))
                .partyId(String.valueOf(party.getPartyId()))
                .status("SUCCESS")
                .build();
    }

    @Transactional(readOnly = true)
    public GetCommunicationPreferencesResponse getCommunicationPreferences(String partyId) {
        log.debug("Fetching communication preferences for party: {}", partyId);
        CommercialParty party = findPartyOrThrow(partyId);

        // Return default/empty preferences - would fetch from separate entity in
        // production
        return GetCommunicationPreferencesResponse.builder()
                .partyId(String.valueOf(party.getPartyId()))
                .emailPreference("NOT_APPLICABLE")
                .smsPreference("NOT_APPLICABLE")
                .phonePreference("NOT_APPLICABLE")
                .build();
    }

    @Transactional
    public UpsertCommunicationPreferencesResponse upsertCommunicationPreferences(String partyId,
            UpsertCommunicationPreferencesRequest request) {
        log.debug("Upserting communication preferences for party: {}", partyId);
        CommercialParty party = findPartyOrThrow(partyId);

        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }

        // Would store in separate CommunicationPreference entity in production
        return UpsertCommunicationPreferencesResponse.builder()
                .partyId(String.valueOf(party.getPartyId()))
                .status("SUCCESS")
                .build();
    }

    @Transactional
    public CreateVehicleForPartyResponse createVehicleForParty(String partyId, CreateVehicleForPartyRequest request) {
        log.debug("Creating vehicle association for party: {}, VIN: {}", partyId,
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
                .status("SUCCESS")
                .build();
    }

    private boolean matchesSearchCriteria(CommercialParty party, SearchPartiesRequest request) {
        if (StringUtils.hasText(request.getName())) {
            String searchTerm = request.getName().toLowerCase(Locale.US);
            boolean matchesLegal = party.getLegalName() != null
                    && party.getLegalName().toLowerCase(Locale.US).contains(searchTerm);
            boolean matchesDisplay = party.getDisplayName() != null
                    && party.getDisplayName().toLowerCase(Locale.US).contains(searchTerm);
            if (!matchesLegal && !matchesDisplay) {
                return false;
            }
        }

        if (StringUtils.hasText(request.getTaxId()) && !request.getTaxId().equals(party.getTaxId())) {
            return false;
        }

        if (StringUtils.hasText(request.getPartyType()) && !request.getPartyType().equals(party.getPartyType())) {
            return false;
        }

        if (StringUtils.hasText(request.getStatus()) && !request.getStatus().equals(party.getStatus())) {
            return false;
        }

        return true;
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
}
