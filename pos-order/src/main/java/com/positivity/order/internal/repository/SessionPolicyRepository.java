package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.SessionPolicy;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The tenant's drawer policy; row-level security leaves at most the bound tenant's one row. */
public interface SessionPolicyRepository extends JpaRepository<SessionPolicy, UUID> {

    Optional<SessionPolicy> findFirstByOrderByCreatedAtAsc();
}
