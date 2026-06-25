package com.positivity.people.internal.service;

import com.positivity.people.internal.dto.Person;
import com.positivity.people.internal.dto.ResolvePersonRequest;
import com.positivity.people.internal.dto.ResolvePersonResponse;
import com.positivity.people.internal.entity.PersonContactPoint;
import com.positivity.people.internal.repository.PersonContactPointRepository;
import com.positivity.people.internal.repository.PersonRepository;
import com.positivity.people.internal.repository.PersonSpecifications;
import com.positivity.people.service.PersonService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PersonServiceImpl implements PersonService {

    private static final int EMAIL_WEIGHT = 60;

    private static final int PHONE_WEIGHT = 25;

    private static final int LAST_NAME_WEIGHT = 10;

    private static final int FIRST_NAME_WEIGHT = 5;

    private final PersonRepository personRepository;

    private final PersonContactPointRepository personContactPointRepository;

    private final PersonWorkPhoneService workPhoneService;

    private final PersonEmailService emailService;

    private final PersonUsernameService usernameService;

    @Value("${pos.people.matching.threshold:30}")
    private int defaultMatchingThreshold;

    @Override
    @NonNull
    public List<Person> getAllPeople(@Nullable String type, @Nullable String q) {
        List<Person> people = personRepository.findAll(PersonSpecifications.directoryFilter(type, q)).stream()
                .map(this::toDto)
                .toList();
        Map<UUID, String> usernames = usernameService.usernamesByPersonId(
                people.stream().map(Person::getId).toList());
        people.forEach(p -> p.setUsername(usernames.get(p.getId())));
        return people;
    }

    @Override
    @NonNull
    public Optional<Person> getPersonById(@NonNull UUID id) {
        return personRepository.findById(id).map(this::toDto).map(dto -> {
            dto.setUsername(usernameService.usernameForPerson(id));
            return dto;
        });
    }

    @Override
    @NonNull
    public List<Person> getPeopleByIds(@NonNull Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Person> people =
                personRepository.findAllById(ids).stream().map(this::toDto).toList();

        // Attach typed contact points (pos-people is SoT for contacts, ADR-0015 I2), batched.
        Map<UUID, List<Person.ContactPointDto>> contactsByPerson =
                personContactPointRepository.findByPersonIdIn(ids).stream()
                        .collect(Collectors.groupingBy(
                                PersonContactPoint::getPersonId,
                                Collectors.mapping(
                                        cp -> new Person.ContactPointDto(
                                                cp.getContactType(), cp.getValue(), cp.isPrimary()),
                                        Collectors.toList())));
        people.forEach(p -> p.setContactPoints(contactsByPerson.getOrDefault(p.getId(), List.of())));

        Map<UUID, String> usernames = usernameService.usernamesByPersonId(ids);
        people.forEach(p -> p.setUsername(usernames.get(p.getId())));
        return people;
    }

    @Override
    @Transactional
    public void replaceContactPoints(@NonNull UUID personId, @NonNull List<Person.ContactPointDto> contactPoints) {
        personContactPointRepository.deleteByPersonId(personId);
        if (contactPoints.isEmpty()) {
            return;
        }
        List<PersonContactPoint> entities = contactPoints.stream()
                .filter(cp -> cp.getContactType() != null && cp.getValue() != null)
                .map(cp -> PersonContactPoint.builder()
                        .personId(personId)
                        .contactType(cp.getContactType())
                        .value(cp.getValue())
                        .isPrimary(cp.isPrimary())
                        .build())
                .toList();
        personContactPointRepository.saveAll(entities);
    }

    @Override
    @Transactional
    @NonNull
    public Person savePerson(@NonNull Person person) {
        // Username is owned by pos-security and linked via user_person_link; it is not a
        // Person attribute and is not persisted here.
        com.positivity.people.internal.entity.Person saved = personRepository.save(toEntity(person));
        workPhoneService.replaceWorkPhones(
                saved.getId(), person.getPhoneNumbers() == null ? List.of() : person.getPhoneNumbers());
        emailService.replaceEmails(saved.getId(), person.getPrimaryEmail(), person.getSecondaryEmail());
        Person dto = toDto(saved);
        dto.setUsername(usernameService.usernameForPerson(saved.getId()));
        return dto;
    }

    @Override
    @Transactional
    @NonNull
    public ResolvePersonResponse resolvePerson(@NonNull ResolvePersonRequest request) {
        String email = normalizeEmail(request.getEmail());
        String phone = normalizePhone(request.getPhone());
        String lastName = normalizeText(request.getLastName());
        String firstName = normalizeText(request.getFirstName());

        if (email == null && phone == null && lastName == null && firstName == null) {
            throw new IllegalArgumentException("At least one of email, phone, lastName, or firstName is required");
        }

        int threshold = resolveThreshold(request.getThreshold());
        Map<UUID, ScoredCandidate> candidates = new HashMap<>();

        if (email != null) {
            List<UUID> emailMatchIds = emailService.findPersonIdsByEmail(email);
            if (!emailMatchIds.isEmpty()) {
                personRepository
                        .findAllById(emailMatchIds)
                        .forEach(person -> addScore(candidates, person, EMAIL_WEIGHT, "EMAIL"));
            }
        }

        if (phone != null) {
            List<UUID> phoneMatchIds = workPhoneService.findPersonIdsByWorkPhone(phone);
            if (!phoneMatchIds.isEmpty()) {
                personRepository
                        .findAllById(phoneMatchIds)
                        .forEach(person -> addScore(candidates, person, PHONE_WEIGHT, "PHONE"));
            }
        }

        if (lastName != null) {
            personRepository
                    .findByLastNameIgnoreCase(lastName)
                    .forEach(person -> addScore(candidates, person, LAST_NAME_WEIGHT, "LAST_NAME"));
        }

        if (firstName != null) {
            personRepository
                    .findByFirstNameIgnoreCase(firstName)
                    .forEach(person -> addScore(candidates, person, FIRST_NAME_WEIGHT, "FIRST_NAME"));
        }

        Optional<ScoredCandidate> best = candidates.values().stream()
                .max(Comparator.comparingInt(ScoredCandidate::getScore)
                        .thenComparing(
                                candidate -> candidate.getPerson().getUpdatedAt(),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(candidate -> candidate.getPerson().getId()));

        if (best.isPresent() && best.get().getScore() >= threshold) {
            ScoredCandidate matched = best.get();
            return ResolvePersonResponse.builder()
                    .personId(matched.getPerson().getId())
                    .matchedExisting(true)
                    .score(matched.getScore())
                    .thresholdApplied(threshold)
                    .matchedBy(new ArrayList<>(matched.getMatchedBy()))
                    .firstName(matched.getPerson().getFirstName())
                    .lastName(matched.getPerson().getLastName())
                    .primaryEmail(
                            emailService.getEmails(matched.getPerson().getId()).primary())
                    .phoneNumbers(
                            workPhoneService.getWorkPhones(matched.getPerson().getId()))
                    .build();
        }

        com.positivity.people.internal.entity.Person entity = new com.positivity.people.internal.entity.Person();
        entity.setFirstName(firstName);
        entity.setLastName(lastName);

        com.positivity.people.internal.entity.Person created = personRepository.save(entity);
        List<String> createdPhones = phone != null ? List.of(phone) : List.of();
        workPhoneService.replaceWorkPhones(created.getId(), createdPhones);
        emailService.replaceEmails(created.getId(), email, null);
        return ResolvePersonResponse.builder()
                .personId(created.getId())
                .matchedExisting(false)
                .score(0)
                .thresholdApplied(threshold)
                .matchedBy(List.of("CREATED"))
                .firstName(created.getFirstName())
                .lastName(created.getLastName())
                .primaryEmail(email)
                .phoneNumbers(createdPhones)
                .build();
    }

    @Override
    public void deletePerson(@NonNull UUID id) {
        personRepository.deleteById(id);
    }

    private int resolveThreshold(Integer thresholdOverride) {
        int threshold = thresholdOverride != null ? thresholdOverride : defaultMatchingThreshold;
        return Math.max(0, threshold);
    }

    private void addScore(
            Map<UUID, ScoredCandidate> candidates,
            com.positivity.people.internal.entity.Person person,
            int score,
            String reason) {
        ScoredCandidate candidate = candidates.computeIfAbsent(person.getId(), ignored -> new ScoredCandidate(person));
        candidate.add(score, reason);
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeEmail(String email) {
        String normalized = normalizeText(email);
        return normalized == null ? null : normalized.toLowerCase();
    }

    private String normalizePhone(String phone) {
        String normalized = normalizeText(phone);
        if (normalized == null) {
            return null;
        }
        String digits = normalized.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return null;
        }
        if (normalized.startsWith("+")) {
            return "+" + digits;
        }
        return digits;
    }

    private Person toDto(com.positivity.people.internal.entity.Person entity) {
        Person dto = new Person();
        dto.setId(entity.getId());
        dto.setFirstName(entity.getFirstName());
        dto.setLastName(entity.getLastName());
        PersonEmailService.EmailPair emails = emailService.getEmails(entity.getId());
        dto.setPrimaryEmail(emails.primary());
        dto.setSecondaryEmail(emails.secondary());
        dto.setPhoneNumbers(workPhoneService.getWorkPhones(entity.getId()));
        // username resolved from pos-security via user_person_link by the caller (single or batched).
        dto.setEmployeeStatus(entity.getStatus());
        return dto;
    }

    private com.positivity.people.internal.entity.Person toEntity(Person dto) {
        com.positivity.people.internal.entity.Person entity = new com.positivity.people.internal.entity.Person();
        entity.setId(dto.getId());
        entity.setFirstName(dto.getFirstName());
        entity.setLastName(dto.getLastName());
        // email + phoneNumbers persisted separately as contact points; username owned by pos-security.
        return entity;
    }

    private static final class ScoredCandidate {

        private final com.positivity.people.internal.entity.Person person;

        private final Set<String> matchedBy = new LinkedHashSet<>();

        private int score;

        private ScoredCandidate(com.positivity.people.internal.entity.Person person) {
            this.person = person;
        }

        private void add(int delta, String reason) {
            this.score += delta;
            this.matchedBy.add(reason);
        }

        private com.positivity.people.internal.entity.Person getPerson() {
            return person;
        }

        private int getScore() {
            return score;
        }

        private Set<String> getMatchedBy() {
            return matchedBy;
        }
    }
}
