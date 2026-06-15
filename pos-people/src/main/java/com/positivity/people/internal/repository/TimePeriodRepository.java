package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.TimePeriod;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TimePeriodRepository extends JpaRepository<TimePeriod, UUID> {

    List<TimePeriod> findAllByTenantIdOrderByStartDateDesc(UUID tenantId);

    Optional<TimePeriod> findByTimePeriodIdAndTenantId(UUID timePeriodId, UUID tenantId);
}
