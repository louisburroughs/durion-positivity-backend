package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.Person;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PersonRepository extends JpaRepository<Person, UUID> {
    boolean existsByUsername(String username);

    boolean existsByEmployeeNumberIgnoreCase(String employeeNumber);

    boolean existsByEmployeeNumberIgnoreCaseAndIdNot(String employeeNumber, UUID id);

    boolean existsByPrimaryEmailIgnoreCase(String primaryEmail);

    boolean existsByPrimaryEmailIgnoreCaseAndIdNot(String primaryEmail, UUID id);

    List<Person> findByLegalNameIgnoreCase(String legalName);

    List<Person> findByPhoneNumbersContains(String phoneNumber);
}
