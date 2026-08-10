package com.positivity.peoplecontact.internal.service;

import com.positivity.peoplecontact.internal.dto.PostalAddressDto;
import com.positivity.peoplecontact.internal.entity.PartyPostalAddress;
import com.positivity.peoplecontact.internal.enums.PartyType;
import com.positivity.peoplecontact.internal.exception.PersonNotFoundException;
import com.positivity.peoplecontact.internal.exception.SemanticValidationException;
import com.positivity.peoplecontact.internal.repository.PartyPostalAddressRepository;
import com.positivity.peoplecontact.internal.repository.PersonContactPointRepository;
import com.positivity.peoplecontact.internal.repository.PersonRepository;
import com.positivity.peoplecontact.service.PostalAddressService;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Postal-address authority implementation (FI-4, #1135). PERSON writes require the person to
 * exist and re-emit the full {@code people-contact.person.updated} identity snapshot;
 * ORGANIZATION writes store the external party id verbatim and emit dedicated
 * organization-address facts. Country codes are validated against ISO 3166-1 alpha-2 and
 * stored upper-case; everything else is trimmed free text, deliberately free of
 * country-specific format rules.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PostalAddressServiceImpl implements PostalAddressService {

    private static final Set<String> ISO_COUNTRIES = Set.of(Locale.getISOCountries());

    private final PartyPostalAddressRepository addressRepository;
    private final PersonRepository personRepository;
    private final PersonContactPointRepository personContactPointRepository;
    private final PeopleContactEventPublisher eventPublisher;

    @Override
    @NonNull
    public Optional<PostalAddressDto> getAddress(@NonNull PartyType partyType, @NonNull UUID partyId) {
        return addressRepository.findByPartyTypeAndPartyId(partyType, partyId).map(PostalAddressServiceImpl::toDto);
    }

    @Override
    @Transactional
    @NonNull
    public PostalAddressDto putAddress(
            @NonNull PartyType partyType, @NonNull UUID partyId, @NonNull PostalAddressDto address) {
        String countryCode = normalizeCountryCode(address.getCountryCode());
        String line1 = trimToNull(address.getLine1());
        if (line1 == null) {
            throw new SemanticValidationException("line1 is required");
        }
        if (partyType == PartyType.PERSON && !personRepository.existsById(partyId)) {
            throw new PersonNotFoundException(partyId);
        }

        PartyPostalAddress entity = addressRepository
                .findByPartyTypeAndPartyId(partyType, partyId)
                .orElseGet(() -> PartyPostalAddress.builder()
                        .partyType(partyType)
                        .partyId(partyId)
                        .build());
        entity.setLine1(line1);
        entity.setLine2(trimToNull(address.getLine2()));
        entity.setCity(trimToNull(address.getCity()));
        entity.setRegion(trimToNull(address.getRegion()));
        entity.setPostalCode(trimToNull(address.getPostalCode()));
        entity.setCountryCode(countryCode);
        // Spring Data's save() is contractually non-null, but the dataflow analyzer (javabugs:S2259)
        // cannot see that; falling back to the managed entity keeps toDto() provably NPE-free.
        PartyPostalAddress saved = Objects.requireNonNullElse(addressRepository.save(entity), entity);

        publishChanged(partyType, partyId, saved);
        return toDto(saved);
    }

    @Override
    @Transactional
    public void deleteAddress(@NonNull PartyType partyType, @NonNull UUID partyId) {
        if (addressRepository.findByPartyTypeAndPartyId(partyType, partyId).isEmpty()) {
            return;
        }
        addressRepository.deleteByPartyTypeAndPartyId(partyType, partyId);
        if (partyType == PartyType.ORGANIZATION) {
            eventPublisher.publishOrganizationAddressRemoved(partyId);
        } else {
            publishChanged(partyType, partyId, null);
        }
    }

    /** Emit the fact matching the party kind: full person snapshot, or the org-address fact. */
    private void publishChanged(PartyType partyType, UUID partyId, @Nullable PartyPostalAddress address) {
        if (partyType == PartyType.ORGANIZATION) {
            // deleteAddress emits the removed fact itself; only updates route here.
            if (address != null) {
                eventPublisher.publishOrganizationAddressUpdated(address);
            }
            return;
        }
        personRepository
                .findById(partyId)
                .ifPresent(person -> eventPublisher.publishPersonUpdated(
                        person, personContactPointRepository.findByPersonId(partyId), address));
    }

    private String normalizeCountryCode(@Nullable String countryCode) {
        String normalized = trimToNull(countryCode);
        if (normalized == null) {
            throw new SemanticValidationException("countryCode is required");
        }
        String upper = normalized.toUpperCase(Locale.ROOT);
        if (!ISO_COUNTRIES.contains(upper)) {
            throw new SemanticValidationException("countryCode must be an ISO 3166-1 alpha-2 code, got: " + upper);
        }
        return upper;
    }

    private static @Nullable String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static PostalAddressDto toDto(PartyPostalAddress entity) {
        return PostalAddressDto.builder()
                .line1(entity.getLine1())
                .line2(entity.getLine2())
                .city(entity.getCity())
                .region(entity.getRegion())
                .postalCode(entity.getPostalCode())
                .countryCode(entity.getCountryCode())
                .build();
    }
}
