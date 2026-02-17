package com.positivity.people.internal.service;

import com.positivity.people.internal.dto.LinkUserToPersonRequest;
import com.positivity.people.internal.dto.PersonResponse;
import com.positivity.people.internal.dto.UserPersonLinkResponse;
import com.positivity.people.internal.entity.Person;
import com.positivity.people.internal.entity.UserPersonLink;
import com.positivity.people.internal.exception.PersonNotFoundException;
import com.positivity.people.internal.exception.UserAlreadyLinkedException;
import com.positivity.people.internal.exception.UserPersonLinkNotFoundException;
import com.positivity.people.internal.repository.PersonRepository;
import com.positivity.people.internal.repository.UserPersonLinkRepository;
import com.positivity.people.service.UserPersonLinkService;
import com.positivity.security.common.SecurityContextHelper;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class UserPersonLinkServiceImpl implements UserPersonLinkService {

    private static final String SYSTEM_USER = "system";

    private final UserPersonLinkRepository linkRepository;
    private final PersonRepository personRepository;

    public UserPersonLinkServiceImpl(
            @NonNull UserPersonLinkRepository linkRepository,
            @NonNull PersonRepository personRepository) {
        this.linkRepository = linkRepository;
        this.personRepository = personRepository;
    }

    @Override
    @NonNull
    public UserPersonLinkResponse linkUserToPerson(@NonNull LinkUserToPersonRequest request) {
        personRepository.findById(request.getPersonId())
                .orElseThrow(() -> new PersonNotFoundException(request.getPersonId()));

        if (linkRepository.existsByUserId(request.getUserId())) {
            throw new UserAlreadyLinkedException(request.getUserId());
        }

        UserPersonLink link = new UserPersonLink();
        link.setUserId(request.getUserId());
        link.setPersonId(request.getPersonId());
        link.setLinkType(request.getLinkType() != null ? request.getLinkType() : "PRIMARY");
        link.setNotes(request.getNotes());
        link.setCreatedBy(SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM_USER));

        UserPersonLink saved = linkRepository.save(link);

        return toResponse(saved);
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
        UserPersonLink link = linkRepository.findByUserId(userId)
                .orElseThrow(() -> new UserPersonLinkNotFoundException(userId));

        Person person = personRepository.findById(link.getPersonId())
                .orElseThrow(() -> new PersonNotFoundException(link.getPersonId()));

        return toPersonResponse(person);
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public List<UUID> findUserIdsByPersonId(@NonNull UUID personId) {
        personRepository.findById(personId)
                .orElseThrow(() -> new PersonNotFoundException(personId));

        return linkRepository.findByPersonId(personId)
                .stream()
                .map(UserPersonLink::getUserId)
                .toList();
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
        return PersonResponse.builder()
                .id(person.getId())
                .firstName(person.getFirstName())
                .lastName(person.getLastName())
                .primaryEmail(person.getPrimaryEmail())
                .secondaryEmail(person.getSecondaryEmail())
                .phoneNumbers(person.getPhoneNumbers())
                .username(person.getUsername())
                .build();
    }
}
