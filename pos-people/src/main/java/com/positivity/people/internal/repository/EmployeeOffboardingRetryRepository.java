package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.EmployeeOffboardingRetry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface EmployeeOffboardingRetryRepository extends JpaRepository<EmployeeOffboardingRetry, UUID> {

}
