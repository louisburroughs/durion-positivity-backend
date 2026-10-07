package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.SessionPolicyChange;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The drawer policy's change history, newest first. */
public interface SessionPolicyChangeRepository extends JpaRepository<SessionPolicyChange, UUID> {

    List<SessionPolicyChange> findAllByOrderByChangedAtDescChangeIdDesc();
}
