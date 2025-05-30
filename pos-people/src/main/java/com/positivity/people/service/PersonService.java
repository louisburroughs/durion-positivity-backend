package com.positivity.people.service;

import com.positivity.people.entity.Person;
import com.positivity.people.repository.PersonRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PersonService {
    private final PersonRepository personRepository;

    public List<Person> getAllPeople() {
        return personRepository.findAll();
    }

    public Optional<Person> getPersonById(Long id) {
        return personRepository.findById(id);
    }

    @Transactional
    public Person savePerson(Person person) {
        if (person.getUsername() != null && !person.getUsername().isBlank() && !validateUsernameWithSecurityService(person.getUsername())) {
                throw new IllegalArgumentException("Username is not valid or does not exist in security service");
            }

        return personRepository.save(person);
    }

    public void deletePerson(Long id) {
        personRepository.deleteById(id);
    }

    // Stub for username validation against pos-security-service
    private boolean validateUsernameWithSecurityService(String username) {
        // TODO: Integrate with pos-security-service
        // For now, accept any username not already in use
        return !personRepository.existsByUsername(username);
    }
}

