package com.positivity.people.internal.service;

import com.positivity.people.internal.dto.Person;
import com.positivity.people.internal.repository.PersonRepository;
import com.positivity.people.service.PersonService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PersonServiceImpl implements PersonService {
    private final PersonRepository personRepository;

    @Override
    public List<Person> getAllPeople() {
        return personRepository.findAll().stream().map(this::toDto).toList();
    }

    @Override
    public Optional<Person> getPersonById(@NonNull UUID id) {
        return personRepository.findById(id).map(this::toDto);
    }

    @Override
    @Transactional
    public Person savePerson(@NonNull Person person) {
        if (person.getUsername() != null && !person.getUsername().isBlank()
                && !validateUsernameWithSecurityService(person.getUsername())) {
            throw new IllegalArgumentException("Username is not valid or does not exist in security service");
        }

        com.positivity.people.internal.entity.Person saved = personRepository.save(toEntity(person));
        return toDto(saved);
    }

    @Override
    public void deletePerson(@NonNull UUID id) {
        personRepository.deleteById(id);
    }

    // Stub for username validation against pos-security-service
    private boolean validateUsernameWithSecurityService(String username) {
        // TODO: Integrate with pos-security-service
        // For now, accept any username not already in use
        return !personRepository.existsByUsername(username);
    }

    private Person toDto(com.positivity.people.internal.entity.Person entity) {
        Person dto = new Person();
        dto.setId(entity.getId());
        dto.setFirstName(entity.getFirstName());
        dto.setLastName(entity.getLastName());
        dto.setPrimaryEmail(entity.getPrimaryEmail());
        dto.setSecondaryEmail(entity.getSecondaryEmail());
        dto.setPhoneNumbers(entity.getPhoneNumbers());
        dto.setUsername(entity.getUsername());
        return dto;
    }

    private com.positivity.people.internal.entity.Person toEntity(Person dto) {
        com.positivity.people.internal.entity.Person entity = new com.positivity.people.internal.entity.Person();
        entity.setId(dto.getId());
        entity.setFirstName(dto.getFirstName());
        entity.setLastName(dto.getLastName());
        entity.setPrimaryEmail(dto.getPrimaryEmail());
        entity.setSecondaryEmail(dto.getSecondaryEmail());
        entity.setPhoneNumbers(dto.getPhoneNumbers());
        entity.setUsername(dto.getUsername());
        return entity;
    }
}
