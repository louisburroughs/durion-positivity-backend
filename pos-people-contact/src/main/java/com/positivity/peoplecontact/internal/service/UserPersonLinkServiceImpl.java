package com.positivity.peoplecontact.internal.service;

import com.positivity.peoplecontact.internal.dto.LinkUserToPersonRequest;
import com.positivity.peoplecontact.internal.dto.PersonResponse;
import com.positivity.peoplecontact.internal.dto.UserPersonLinkResponse;
import com.positivity.peoplecontact.internal.entity.Person;
import com.positivity.peoplecontact.internal.entity.UserPersonLink;
import com.positivity.peoplecontact.internal.exception.PersonNotFoundException;
import com.positivity.peoplecontact.internal.exception.UserAlreadyLinkedException;
import com.positivity.peoplecontact.internal.exception.UserPersonLinkNotFoundException;
import com.positivity.peoplecontact.internal.repository.PersonRepository;
import com.positivity.peoplecontact.internal.repository.UserPersonLinkRepository;
import com.positivity.peoplecontact.service.UserPersonLinkService;
import com.positivity.security.common.SecurityContextHelper;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class UserPersonLinkServiceImpl implements UserPersonLinkService {

    private static final String SYSTEM_USER = "system";

    private final UserPersonLinkRepository linkRepository;

    private final PeopleContactEventPublisher eventPublisher;

    private final PersonRepository personRepository;

    private final PersonWorkPhoneService workPhoneService;

    private final PersonEmailService emailService;

    private final PersonUsernameService usernameService;

    public UserPersonLinkServiceImpl(
            @NonNull UserPersonLinkRepository linkRepository,
            @NonNull PersonRepository personRepository,
            @NonNull PersonWorkPhoneService workPhoneService,
            @NonNull PersonEmailService emailService,
            @NonNull PersonUsernameService usernameService,
            @NonNull PeopleContactEventPublisher eventPublisher) {
        this.linkRepository = linkRepository;
        this.personRepository = personRepository;
        this.workPhoneService = workPhoneService;
        this.emailService = emailService;
        this.usernameService = usernameService;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public boolean linkExistsByUsername(@NonNull String username) {
        return linkRepository.existsByUsername(username);
    }

    @Override
    public boolean linkExistsByUsernameAndPersonId(@NonNull String username, @NonNull UUID personId) {
        return linkRepository.existsByUsernameAndPerson_Id(username, personId);
    }

    @Override
    @NonNull
    public UserPersonLinkResponse createUserLink(@NonNull String username, @NonNull UUID personId) {
        Person person = personRepository.findById(personId).orElseThrow(() -> new PersonNotFoundException(personId));

        if (linkRepository.existsByUsername(username)) {
            UserPersonLink existingLink =
                    linkRepository.findByUsername(username).orElseThrow(() -> new UserAlreadyLinkedException(username));
            if (existingLink.getPersonId().equals(personId)) {
                return toResponse(existingLink);
            }
            throw new UserAlreadyLinkedException(username, existingLink.getPersonId(), personId);
        }

        UserPersonLink link = new UserPersonLink();
        link.setUsername(username);
        link.setPerson(person);
        link.setLinkType("PRIMARY");
        link.setCreatedBy(SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM_USER));
        try {
            UserPersonLink saved = linkRepository.save(link);
            eventPublisher.publishLinkUpdated(saved);
            return toResponse(saved);
        } catch (DataIntegrityViolationException e) {
            UserPersonLink existingLink =
                    linkRepository.findByUsername(username).orElseThrow(() -> e);
            if (existingLink.getPersonId().equals(personId)) {
                return toResponse(existingLink);
            }
            throw new UserAlreadyLinkedException(username, existingLink.getPersonId(), personId);
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
        UserPersonLinkResponse response = createUserLink(request.getUsername(), request.getPersonId());
        if (request.getNotes() != null || request.getLinkType() != null) {
            var existingLink = linkRepository.findByUsername(request.getUsername());
            if (existingLink.isPresent()) {
                UserPersonLink link = existingLink.get();
                if (request.getLinkType() != null && !request.getLinkType().isBlank()) {
                    link.setLinkType(request.getLinkType());
                }
                if (request.getNotes() != null) {
                    link.setNotes(request.getNotes());
                }
                UserPersonLink saved = linkRepository.save(link);
                eventPublisher.publishLinkUpdated(saved);
                response = toResponse(saved);
            }
        }
        return response;
    }

    @Override
    public void unlinkUserFromPerson(@NonNull String username) {
        UserPersonLink link = linkRepository
                .findByUsername(username)
                .orElseThrow(() -> new UserPersonLinkNotFoundException(username));
        linkRepository.delete(link);
        eventPublisher.publishLinkRemoved(link);
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public PersonResponse findPersonByUsername(@NonNull String username) {
        UserPersonLink link = linkRepository
                .findByUsername(username)
                .orElseThrow(() -> new UserPersonLinkNotFoundException(username));

        Person person = personRepository
                .findById(link.getPersonId())
                .orElseThrow(() -> new PersonNotFoundException(link.getPersonId()));

        return toPersonResponse(person);
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public List<String> findUsernamesByPersonId(@NonNull UUID personId) {
        personRepository.findById(personId).orElseThrow(() -> new PersonNotFoundException(personId));

        return linkRepository.findByPerson_Id(personId).stream()
                .map(UserPersonLink::getUsername)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public UserPersonLinkResponse findLinkByPersonId(@NonNull UUID personId) {
        personRepository.findById(personId).orElseThrow(() -> new PersonNotFoundException(personId));

        UserPersonLink link = linkRepository
                .findFirstByPerson_IdAndStatusOrderByCreatedAtDesc(
                        personId, com.positivity.peoplecontact.internal.enums.UserLinkStatus.ACTIVE)
                .orElseThrow(() -> new UserPersonLinkNotFoundException(personId));

        return toResponse(link);
    }

    private UserPersonLinkResponse toResponse(UserPersonLink link) {
        return UserPersonLinkResponse.builder()
                .linkId(link.getId())
                .username(link.getUsername())
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
                .username(usernameService.usernameForPerson(person.getId()))
                .build();
    }
}
