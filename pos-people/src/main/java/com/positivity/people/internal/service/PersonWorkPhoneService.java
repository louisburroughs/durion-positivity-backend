package com.positivity.people.internal.service;

import com.positivity.people.internal.entity.PersonContactPoint;
import com.positivity.people.internal.enums.ContactPointType;
import com.positivity.people.internal.repository.PersonContactPointRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes a person's work phone numbers through
 * {@link PersonContactPoint} rows of type {@link ContactPointType#PHONE_WORK},
 * consolidating the former {@code person_phone_numbers} element collection into the
 * single contact-point store.
 */
@Service
@RequiredArgsConstructor
public class PersonWorkPhoneService {

    private final PersonContactPointRepository contactPointRepository;

    /** Work phone values for a person, primary first. */
    public @NonNull List<String> getWorkPhones(@NonNull UUID personId) {
        return contactPointRepository.findByPersonIdAndContactType(personId, ContactPointType.PHONE_WORK).stream()
                .sorted(Comparator.comparing(PersonContactPoint::isPrimary)
                        .reversed()
                        .thenComparing(p -> p.getCreatedAt() == null ? Instant.EPOCH : p.getCreatedAt()))
                .map(PersonContactPoint::getValue)
                .filter(Objects::nonNull)
                .toList();
    }

    /** Batch variant: personId → work phone values. */
    public @NonNull Map<UUID, List<String>> getWorkPhones(@NonNull Collection<UUID> personIds) {
        if (personIds.isEmpty()) {
            return Map.of();
        }
        return contactPointRepository.findByPersonIdIn(personIds).stream()
                .filter(p -> p.getContactType() == ContactPointType.PHONE_WORK && p.getValue() != null)
                .collect(Collectors.groupingBy(
                        PersonContactPoint::getPersonId,
                        Collectors.mapping(PersonContactPoint::getValue, Collectors.toList())));
    }

    /** Replace all of a person's work phones with the given list (first becomes primary). */
    @Transactional
    public void replaceWorkPhones(@NonNull UUID personId, @NonNull List<String> phones) {
        contactPointRepository.deleteByPersonIdAndContactType(personId, ContactPointType.PHONE_WORK);
        boolean primary = true;
        for (String phone : phones) {
            if (phone == null || phone.isBlank()) {
                continue;
            }
            contactPointRepository.save(PersonContactPoint.builder()
                    .personId(personId)
                    .contactType(ContactPointType.PHONE_WORK)
                    .value(phone.trim())
                    .isPrimary(primary)
                    .build());
            primary = false;
        }
    }

    /** Person ids that have the given value as a work phone (duplicate detection). */
    public @NonNull List<UUID> findPersonIdsByWorkPhone(@NonNull String phone) {
        return contactPointRepository.findByContactTypeAndValue(ContactPointType.PHONE_WORK, phone).stream()
                .map(PersonContactPoint::getPersonId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }
}
