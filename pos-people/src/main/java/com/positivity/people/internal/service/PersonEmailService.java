package com.positivity.people.internal.service;

import com.positivity.people.internal.entity.PersonContactPoint;
import com.positivity.people.internal.enums.ContactPointType;
import com.positivity.people.internal.repository.PersonContactPointRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes a person's primary/secondary email through {@link PersonContactPoint}
 * rows of type {@link ContactPointType#EMAIL} (primary = {@code isPrimary} true),
 * replacing the former primary_email / secondary_email columns on Person.
 */
@Service
@RequiredArgsConstructor
public class PersonEmailService {

    private final PersonContactPointRepository contactPointRepository;

    /** Primary + secondary email for a person. */
    public record EmailPair(
            @Nullable String primary, @Nullable String secondary) {}

    public @NonNull EmailPair getEmails(@NonNull UUID personId) {
        List<PersonContactPoint> points =
                contactPointRepository.findByPersonIdAndContactType(personId, ContactPointType.EMAIL);
        String primary = points.stream()
                .filter(PersonContactPoint::isPrimary)
                .map(PersonContactPoint::getValue)
                .findFirst()
                .orElseGet(() -> points.stream()
                        .map(PersonContactPoint::getValue)
                        .findFirst()
                        .orElse(null));
        String secondary = points.stream()
                .filter(p -> !p.isPrimary())
                .map(PersonContactPoint::getValue)
                .filter(value -> !Objects.equals(value, primary))
                .findFirst()
                .orElse(null);
        return new EmailPair(primary, secondary);
    }

    /** Replace a person's email contact points with the given primary/secondary (blanks skipped). */
    @Transactional
    public void replaceEmails(@NonNull UUID personId, @Nullable String primary, @Nullable String secondary) {
        contactPointRepository.deleteByPersonIdAndContactType(personId, ContactPointType.EMAIL);
        if (primary != null && !primary.isBlank()) {
            contactPointRepository.save(build(personId, primary.trim(), true));
        }
        if (secondary != null && !secondary.isBlank()) {
            contactPointRepository.save(build(personId, secondary.trim(), false));
        }
    }

    /** Person ids that hold the given value as any EMAIL contact point (case-insensitive). */
    public @NonNull List<UUID> findPersonIdsByEmail(@NonNull String email) {
        return contactPointRepository.findByContactTypeAndValueIgnoreCase(ContactPointType.EMAIL, email).stream()
                .map(PersonContactPoint::getPersonId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /** Person ids that hold the given value as a PRIMARY email (case-insensitive); duplicate detection. */
    public @NonNull List<UUID> findPersonIdsByPrimaryEmail(@NonNull String email) {
        return contactPointRepository
                .findByContactTypeAndIsPrimaryAndValueIgnoreCase(ContactPointType.EMAIL, true, email)
                .stream()
                .map(PersonContactPoint::getPersonId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private PersonContactPoint build(UUID personId, String value, boolean primary) {
        return PersonContactPoint.builder()
                .personId(personId)
                .contactType(ContactPointType.EMAIL)
                .value(value)
                .isPrimary(primary)
                .build();
    }
}
