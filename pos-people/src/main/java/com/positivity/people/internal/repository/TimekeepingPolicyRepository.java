package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.TimekeepingPolicy;
import com.positivity.people.internal.enums.TimekeepingPolicyScopeType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TimekeepingPolicyRepository extends JpaRepository<TimekeepingPolicy, UUID> {

    List<TimekeepingPolicy> findByScopeType(TimekeepingPolicyScopeType scopeType);

    List<TimekeepingPolicy> findByScopeTypeAndScopeId(TimekeepingPolicyScopeType scopeType, UUID scopeId);

    List<TimekeepingPolicy> findByScopeTypeAndScopeIdIn(
            TimekeepingPolicyScopeType scopeType, Collection<UUID> scopeIds);
}
