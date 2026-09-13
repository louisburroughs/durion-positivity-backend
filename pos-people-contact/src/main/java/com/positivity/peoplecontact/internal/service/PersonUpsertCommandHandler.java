package com.positivity.peoplecontact.internal.service;

import com.positivity.domainevents.peoplecontact.PersonUpdatedV1;
import com.positivity.domainevents.peoplecontact.PersonUpsertRequestedV1;
import com.positivity.peoplecontact.internal.entity.Person;
import com.positivity.peoplecontact.internal.entity.PersonContactPoint;
import com.positivity.peoplecontact.internal.entity.ProcessedEvent;
import com.positivity.peoplecontact.internal.enums.ContactPointType;
import com.positivity.peoplecontact.internal.enums.PartyType;
import com.positivity.peoplecontact.internal.repository.PartyPostalAddressRepository;
import com.positivity.peoplecontact.internal.repository.PersonContactPointRepository;
import com.positivity.peoplecontact.internal.repository.PersonRepository;
import com.positivity.peoplecontact.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies {@code people-contact.person.upsert-requested} commands (ADR-0044 §2, #875).
 *
 * <p>HR flows in pos-people send these commands instead of writing identity data directly.
 * The upsert is full-attribute last-writer-wins per personId; the confirming
 * {@code people-contact.person.updated} fact is emitted in the same transaction via the outbox,
 * which feeds the sender's replica, and the {@code processed_events} idempotency row commits in
 * the same transaction so redelivery can never regress a newer write.
 *
 * <p>Contacts arrive in one of the two shapes the command declares: the flattened HR set
 * (primary/secondary email plus work phones), or the full typed {@code contactPoints} list that
 * pos-customer's CRM sends. A non-null typed list wins and replaces every contact point the
 * person has, which is the shape's whole purpose — the CRM manages arbitrary contact types the
 * flattened set cannot express (#877, issue #1977).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonUpsertCommandHandler {

    private final Clock clock;
    private final PersonRepository personRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final PersonContactPointRepository personContactPointRepository;
    private final PartyPostalAddressRepository partyPostalAddressRepository;
    private final PersonWorkPhoneService workPhoneService;
    private final PersonEmailService emailService;
    private final PeopleContactEventPublisher eventPublisher;

    @Transactional
    public void apply(@NonNull String commandEventId, @NonNull PersonUpsertRequestedV1 command) {
        Person entity = personRepository.findById(command.personId()).orElseGet(Person::new);
        entity.setId(command.personId());
        entity.setFirstName(normalize(command.firstName()));
        entity.setLastName(normalize(command.lastName()));
        entity.setPreferredName(normalize(command.preferredName()));
        Person saved = personRepository.save(entity);

        if (command.contactPoints() != null) {
            replaceTypedContactPoints(saved.getId(), command.contactPoints());
        } else {
            workPhoneService.replaceWorkPhones(
                    saved.getId(), command.workPhones() == null ? List.of() : command.workPhones());
            emailService.replaceEmails(
                    saved.getId(), normalize(command.primaryEmail()), normalize(command.secondaryEmail()));
        }

        // The HR upsert command carries no postal address; the stored one (FI-4, #1135) is
        // untouched by design and re-attached to the confirming fact so replicas keep it.
        eventPublisher.publishPersonUpdated(
                saved,
                personContactPointRepository.findByPersonId(saved.getId()),
                partyPostalAddressRepository
                        .findByPartyTypeAndPartyId(PartyType.PERSON, saved.getId())
                        .orElse(null));
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(commandEventId)
                .owner("people")
                .processedAt(Instant.now(clock))
                .build());
        log.info("Applied person upsert command personId={} eventId={}", saved.getId(), commandEventId);
    }

    /**
     * Replaces every contact point of a person with the command's typed list. Types this module
     * does not model (pos-customer has a {@code FAX} the contact taxonomy here does not) are
     * dropped with a warning rather than failing the command: the listener treats a thrown
     * exception as a permanent failure and would discard the identity write along with it.
     */
    private void replaceTypedContactPoints(
            @NonNull UUID personId, @NonNull List<PersonUpdatedV1.ContactPointV1> contactPoints) {
        personContactPointRepository.deleteByPersonId(personId);
        // Flush the removals before the replacements so the confirming fact's read below sees
        // exactly the new set, whatever order Hibernate would otherwise pick for the two.
        personContactPointRepository.flush();
        for (PersonUpdatedV1.ContactPointV1 point : contactPoints) {
            String value = normalize(point.value());
            if (value == null) {
                continue;
            }
            ContactPointType type = contactPointType(point.contactType());
            if (type == null) {
                log.warn(
                        "Dropping contact point of unsupported type '{}' for personId={}",
                        point.contactType(),
                        personId);
                continue;
            }
            personContactPointRepository.save(PersonContactPoint.builder()
                    .personId(personId)
                    .contactType(type)
                    .value(value)
                    .isPrimary(point.primary())
                    .build());
        }
    }

    private static ContactPointType contactPointType(String contactType) {
        if (contactType == null) {
            return null;
        }
        try {
            return ContactPointType.valueOf(contactType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
