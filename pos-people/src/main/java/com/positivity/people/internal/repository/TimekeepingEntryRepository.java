package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.TimekeepingEntry;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TimekeepingEntryRepository extends JpaRepository<TimekeepingEntry, UUID> {

    boolean existsByTenantIdAndSourceSystemAndSourceSessionId(UUID tenantId, String sourceSystem,
            UUID sourceSessionId);
}