package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.Person;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PersonRepository extends JpaRepository<Person, UUID>, JpaSpecificationExecutor<Person> {

    boolean existsByUsername(String username);

    boolean existsByEmployeeNumberIgnoreCase(String employeeNumber);

    boolean existsByEmployeeNumberIgnoreCaseAndIdNot(String employeeNumber, UUID id);

    List<Person> findByLegalNameIgnoreCase(String legalName);

    List<Person> findByLastNameIgnoreCase(String lastName);

    List<Person> findByFirstNameIgnoreCase(String firstName);
}
