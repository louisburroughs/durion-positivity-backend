package com.positivity.customer.service;

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
import com.positivity.customer.internal.entity.Contact;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.repository.ContactRepository;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PartyService {

    private final CommercialPartyRepository partyRepository;
    private final ContactRepository contactRepository;

    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT.withLocale(Locale.US);

    @Transactional
    public CreateCommercialAccountResponse createCommercialAccount(CreateCommercialAccountRequest request) {
        if (request == null || !StringUtils.hasText(request.getLegalName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "legalName is required");
        }

        CommercialParty party = new CommercialParty();
        party.setLegalName(request.getLegalName());
        party.setDisplayName(request.getDisplayName());
        party.setTaxId(request.getTaxId());
        party.setBillingTermsId(request.getBillingTermsId());
        party.setPartyType(StringUtils.hasText(request.getPartyType()) ? request.getPartyType() : "ORGANIZATION");
        party.setStatus("ACTIVE");
        party.setPartyNumber(generatePartyNumber());
        if (request.getExternalIdentifiers() != null) {
            party.getExternalIdentifiers().putAll(request.getExternalIdentifiers());
        }

        Contact contact = buildContactForParty(request, party);
        party.getContacts().add(contact);

        CommercialParty saved = partyRepository.save(party);
        contactRepository.save(contact);

        return CreateCommercialAccountResponse.builder()
                .partyId(String.valueOf(saved.getId()))
                .legalName(saved.getLegalName())
                .status(saved.getStatus())
                .createdAt(saved.getCreatedAt())
                .duplicateCandidates(new ArrayList<>())
                .build();
    }

    @Transactional(readOnly = true)
    public GetPartyResponse getParty(String partyId) {
        CommercialParty party = findPartyOrThrow(partyId);
        return GetPartyResponse.builder()
                .partyId(String.valueOf(party.getId()))
                .partyType(party.getPartyType())
                .legalName(party.getLegalName())
                .displayName(party.getDisplayName())
                .taxId(party.getTaxId())
                .status(party.getStatus())
                .billingTermsId(party.getBillingTermsId())
                .createdAt(party.getCreatedAt() != null ? ISO_FORMATTER.format(party.getCreatedAt()) : null)
                .modifiedAt(party.getModifiedAt() != null ? ISO_FORMATTER.format(party.getModifiedAt()) : null)
                .build();
    }

    @Transactional(readOnly = true)
    public GetContactsWithRolesResponse getContactsWithRoles(String partyId) {
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
                .partyId(String.valueOf(party.getId()))
                .contacts(contactDtos)
                .build();
    }

    private CommercialParty findPartyOrThrow(String partyId) {
        UUID id = parseUuid(partyId);
        return partyRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "party not found"));
    }

    private UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
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

        return SearchPartiesResponse.builder()
                .results(summaries)
                .totalCount(summaries.size())
                .pageNumber(0)
                .pageSize(summaries.size())
                .build();
    }

    @Transactional
    public MergePartiesResponse mergeParties(String survivorPartyId, MergePartiesRequest request) {
        if (request == null || !StringUtils.hasText(request.getLosingPartyId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "losingPartyId is required");
        }
        if (!StringUtils.hasText(request.getJustification())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "justification is required");
        }

        Party survivor = findPartyOrThrow(survivorPartyId);
        Party loser = findPartyOrThrow(request.getLosingPartyId());

        if (survivor.getId().equals(loser.getId())) {
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
        loser.setStatus("MERGED");

        partyRepository.save(survivor);
        partyRepository.save(loser);

        return MergePartiesResponse.builder()
                .survivorPartyId(String.valueOf(survivor.getId()))
                .losingPartyId(String.valueOf(loser.getId()))
                .status("COMPLETED")
                .build();
    }

    @Transactional
    public UpdateContactRolesResponse updateContactRoles(String partyId, String contactId,
            UpdateContactRolesRequest request) {
        CommercialParty party = findPartyOrThrow(partyId);
        UUID contactIdUuid = parseUuid(contactId);

        Contact contact = contactRepository.findById(contactIdUuid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "contact not found"));

        if (!contact.getCommercialParty().getId().equals(party.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "contact does not belong to this party");
        }

        // In a real implementation, would store roles in a separate ContactRole entity
        // For now, return success response
        return UpdateContactRolesResponse.builder()
                .contactId(String.valueOf(contact.getId()))
                .partyId(String.valueOf(party.getId()))
                .status("SUCCESS")
                .build();
    }

    @Transactional(readOnly = true)
    public GetCommunicationPreferencesResponse getCommunicationPreferences(String partyId) {
        CommercialParty party = findPartyOrThrow(partyId);

        // Return default/empty preferences - would fetch from separate entity in
        // production
        return GetCommunicationPreferencesResponse.builder()
                .partyId(String.valueOf(party.getId()))
                .emailPreference("NOT_APPLICABLE")
                .smsPreference("NOT_APPLICABLE")
                .phonePreference("NOT_APPLICABLE")
                .build();
    }

    @Transactional
    public UpsertCommunicationPreferencesResponse upsertCommunicationPreferences(String partyId,
            UpsertCommunicationPreferencesRequest request) {
        CommercialParty party = findPartyOrThrow(partyId);

        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }

        // Would store in separate CommunicationPreference entity in production
        return UpsertCommunicationPreferencesResponse.builder()
                .partyId(String.valueOf(party.getId()))
                .status("SUCCESS")
                .build();
    }

    @Transactional
    public CreateVehicleForPartyResponse createVehicleForParty(String partyId, CreateVehicleForPartyRequest request) {
        if (request == null || !StringUtils.hasText(request.getVinNumber())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "vinNumber is required");
        }

        CommercialParty party = findPartyOrThrow(partyId);

        if (party.getVehicleVins().contains(request.getVinNumber())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Vehicle already associated with this party");
        }

        party.getVehicleVins().add(request.getVinNumber());
        partyRepository.save(party);

        return CreateVehicleForPartyResponse.builder()
                .partyId(String.valueOf(party.getId()))
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
                .partyId(String.valueOf(party.getId()))
                .legalName(party.getLegalName())
                .displayName(party.getDisplayName())
                .partyType(party.getPartyType())
                .status(party.getStatus())
                .createdAt(party.getCreatedAt() != null ? ISO_FORMATTER.format(party.getCreatedAt()) : null)
                .build();
    }
}
