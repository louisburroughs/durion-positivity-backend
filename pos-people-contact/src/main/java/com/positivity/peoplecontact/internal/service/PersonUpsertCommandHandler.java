package com.positivity.peoplecontact.internal.service;

import com.positivity.domainevents.peoplecontact.PersonUpsertRequestedV1;
import com.positivity.peoplecontact.internal.entity.Person;
import com.positivity.peoplecontact.internal.entity.ProcessedEvent;
import com.positivity.peoplecontact.internal.repository.PersonContactPointRepository;
import com.positivity.peoplecontact.internal.repository.PersonRepository;
import com.positivity.peoplecontact.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonUpsertCommandHandler {

    private final Clock clock;
    private final PersonRepository personRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final PersonContactPointRepository personContactPointRepository;
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

        workPhoneService.replaceWorkPhones(
                saved.getId(), command.workPhones() == null ? List.of() : command.workPhones());
        emailService.replaceEmails(
                saved.getId(), normalize(command.primaryEmail()), normalize(command.secondaryEmail()));

        eventPublisher.publishPersonUpdated(saved, personContactPointRepository.findByPersonId(saved.getId()));
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(commandEventId)
                .owner("people")
                .processedAt(Instant.now(clock))
                .build());
        log.info("Applied person upsert command personId={} eventId={}", saved.getId(), commandEventId);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
