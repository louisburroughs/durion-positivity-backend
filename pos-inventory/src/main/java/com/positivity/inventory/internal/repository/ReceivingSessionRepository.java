package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.ReceivingSession;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReceivingSessionRepository extends JpaRepository<ReceivingSession, UUID> {
}