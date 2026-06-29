package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.Employee;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeRepository extends JpaRepository<Employee, UUID> {

    Optional<Employee> findByPersonId(UUID personId);

    Optional<Employee> findByEmployeeNumberIgnoreCase(String employeeNumber);

    List<Employee> findByPersonIdIn(Collection<UUID> personIds);

    boolean existsByEmployeeNumberIgnoreCase(String employeeNumber);

    boolean existsByEmployeeNumberIgnoreCaseAndPersonIdNot(String employeeNumber, UUID personId);
}
