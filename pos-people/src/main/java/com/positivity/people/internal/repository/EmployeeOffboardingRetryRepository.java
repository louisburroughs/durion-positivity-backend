package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.EmployeeOffboardingRetry;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EmployeeOffboardingRetryRepository extends JpaRepository<EmployeeOffboardingRetry, UUID> {}
