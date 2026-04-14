package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.Person;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PersonRepository extends JpaRepository<Person, UUID> {

    Optional<Person> findByPrimaryEmailIgnoreCase(String primaryEmail);

    Optional<Person> findBySecondaryEmailIgnoreCase(String secondaryEmail);

    boolean existsByUsername(String username);

    boolean existsByEmployeeNumberIgnoreCase(String employeeNumber);

    boolean existsByEmployeeNumberIgnoreCaseAndIdNot(String employeeNumber, UUID id);

    boolean existsByPrimaryEmailIgnoreCase(String primaryEmail);

    boolean existsByPrimaryEmailIgnoreCaseAndIdNot(String primaryEmail, UUID id);

    List<Person> findByLegalNameIgnoreCase(String legalName);

    List<Person> findByLastNameIgnoreCase(String lastName);

    List<Person> findByFirstNameIgnoreCase(String firstName);

    List<Person> findByPhoneNumbersContains(String phoneNumber);
}
