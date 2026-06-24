package com.positivity.people.internal.service;

import com.positivity.people.internal.dto.InactivePersonActiveUserResponse;
import com.positivity.people.internal.dto.LinkUserToPersonRequest;
import com.positivity.people.internal.dto.PersonResponse;
import com.positivity.people.internal.dto.UserPersonLinkResponse;
import com.positivity.people.internal.entity.Person;
import com.positivity.people.internal.entity.UserPersonLink;
import com.positivity.people.internal.enums.EmployeeStatus;
import com.positivity.people.internal.enums.UserLinkStatus;
import com.positivity.people.internal.exception.PersonNotFoundException;
import com.positivity.people.internal.exception.UserAlreadyLinkedException;
import com.positivity.people.internal.exception.UserPersonLinkNotFoundException;
import com.positivity.people.internal.repository.PersonRepository;
import com.positivity.people.internal.repository.UserPersonLinkRepository;
import com.positivity.people.service.UserPersonLinkService;
import com.positivity.security.common.SecurityContextHelper;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class UserPersonLinkServiceImpl implements UserPersonLinkService {

    private static final String SYSTEM_USER = "system";

    /** Person statuses that should NOT retain an active user (ADR-0015 §4). */
    private static final Set<EmployeeStatus> INACTIVE_PERSON_STATUSES =
            EnumSet.of(EmployeeStatus.SUSPENDED, EmployeeStatus.TERMINATED, EmployeeStatus.DISABLED);

    private final UserPersonLinkRepository linkRepository;

    private final PersonRepository personRepository;

    private final PersonWorkPhoneService workPhoneService;

    private final PersonEmailService emailService;

    public UserPersonLinkServiceImpl(
            @NonNull UserPersonLinkRepository linkRepository,
            @NonNull PersonRepository personRepository,
            @NonNull PersonWorkPhoneService workPhoneService,
            @NonNull PersonEmailService emailService) {
        this.linkRepository = linkRepository;
        this.personRepository = personRepository;
        this.workPhoneService = workPhoneService;
        this.emailService = emailService;
    }

    @Override
    public boolean linkExistsByUserId(@NonNull UUID userId) {
        return linkRepository.existsByUserId(userId);
    }

    @Override
    public boolean linkExistsByUserIdAndPersonId(@NonNull UUID userId, @NonNull UUID personId) {
        return linkRepository.existsByUserIdAndPerson_Id(userId, personId);
    }

    @Override
    @NonNull
    public UserPersonLinkResponse createUserLink(@NonNull UUID userId, @NonNull UUID personId) {
        Person person = personRepository.findById(personId).orElseThrow(() -> new PersonNotFoundException(personId));

        if (linkRepository.existsByUserId(userId)) {
            UserPersonLink existingLink =
                    linkRepository.findByUserId(userId).orElseThrow(() -> new UserAlreadyLinkedException(userId));
            if (existingLink.getPersonId().equals(personId)) {
                return toResponse(existingLink);
            }
            throw new UserAlreadyLinkedException(userId, existingLink.getPersonId(), personId);
        }

        UserPersonLink link = new UserPersonLink();
        link.setUserId(userId);
        link.setPerson(person);
        link.setLinkType("PRIMARY");
        link.setCreatedBy(SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM_USER));
        try {
            UserPersonLink saved = linkRepository.save(link);
            return toResponse(saved);
        } catch (DataIntegrityViolationException e) {
            UserPersonLink existingLink = linkRepository.findByUserId(userId).orElseThrow(() -> e);
            if (existingLink.getPersonId().equals(personId)) {
                return toResponse(existingLink);
            }
            throw new UserAlreadyLinkedException(userId, existingLink.getPersonId(), personId);
        }
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public List<UserPersonLinkResponse> getUserLinks(@NonNull UUID personId) {
        personRepository.findById(personId).orElseThrow(() -> new PersonNotFoundException(personId));

        return linkRepository.findByPerson_Id(personId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @NonNull
    public UserPersonLinkResponse linkUserToPerson(@NonNull LinkUserToPersonRequest request) {
        UserPersonLinkResponse response = createUserLink(request.getUserId(), request.getPersonId());
        if (request.getNotes() != null || request.getLinkType() != null) {
            var existingLink = linkRepository.findByUserId(request.getUserId());
            if (existingLink.isPresent()) {
                UserPersonLink link = existingLink.get();
                if (request.getLinkType() != null && !request.getLinkType().isBlank()) {
                    link.setLinkType(request.getLinkType());
                }
                if (request.getNotes() != null) {
                    link.setNotes(request.getNotes());
                }
                response = toResponse(linkRepository.save(link));
            }
        }
        return response;
    }

    @Override
    public void unlinkUserFromPerson(@NonNull UUID userId) {
        if (!linkRepository.existsByUserId(userId)) {
            throw new UserPersonLinkNotFoundException(userId);
        }
        linkRepository.deleteByUserId(userId);
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public PersonResponse findPersonByUserId(@NonNull UUID userId) {
        UserPersonLink link =
                linkRepository.findByUserId(userId).orElseThrow(() -> new UserPersonLinkNotFoundException(userId));

        Person person = personRepository
                .findById(link.getPersonId())
                .orElseThrow(() -> new PersonNotFoundException(link.getPersonId()));

        return toPersonResponse(person);
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public List<UUID> findUserIdsByPersonId(@NonNull UUID personId) {
        personRepository.findById(personId).orElseThrow(() -> new PersonNotFoundException(personId));

        return linkRepository.findByPerson_Id(personId).stream()
                .map(UserPersonLink::getUserId)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public UserPersonLinkResponse findLinkByPersonId(@NonNull UUID personId) {
        personRepository.findById(personId).orElseThrow(() -> new PersonNotFoundException(personId));

        UserPersonLink link = linkRepository
                .findFirstByPerson_IdAndStatusOrderByCreatedAtDesc(
                        personId, com.positivity.people.internal.enums.UserLinkStatus.ACTIVE)
                .orElseThrow(() -> new UserPersonLinkNotFoundException(personId));

        return toResponse(link);
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public List<InactivePersonActiveUserResponse> findActiveUsersForInactivePersons() {
        return linkRepository.findByStatusAndPerson_StatusIn(UserLinkStatus.ACTIVE, INACTIVE_PERSON_STATUSES).stream()
                .map(this::toInactivePersonResponse)
                .toList();
    }

    private InactivePersonActiveUserResponse toInactivePersonResponse(UserPersonLink link) {
        Person person = link.getPerson();
        return InactivePersonActiveUserResponse.builder()
                .linkId(link.getId())
                .userId(link.getUserId())
                .personId(link.getPersonId())
                .personStatus(person != null ? person.getStatus() : null)
                .personStatusEffectiveAt(person != null ? person.getStatusEffectiveAt() : null)
                .build();
    }

    private UserPersonLinkResponse toResponse(UserPersonLink link) {
        return UserPersonLinkResponse.builder()
                .linkId(link.getId())
                .userId(link.getUserId())
                .personId(link.getPersonId())
                .linkType(link.getLinkType())
                .createdAt(link.getCreatedAt())
                .createdBy(link.getCreatedBy())
                .notes(link.getNotes())
                .build();
    }

    private PersonResponse toPersonResponse(Person person) {
        PersonEmailService.EmailPair emails = emailService.getEmails(person.getId());
        return PersonResponse.builder()
                .id(person.getId())
                .firstName(person.getFirstName())
                .lastName(person.getLastName())
                .primaryEmail(emails.primary())
                .secondaryEmail(emails.secondary())
                .phoneNumbers(workPhoneService.getWorkPhones(person.getId()))
                .username(person.getUsername())
                .build();
    }
}
