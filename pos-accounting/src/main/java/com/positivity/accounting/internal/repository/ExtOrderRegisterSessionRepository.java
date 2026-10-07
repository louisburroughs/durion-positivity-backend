package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.ExtOrderRegisterSession;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** The replica of pos-order's register sessions (#2571, #2573). */
public interface ExtOrderRegisterSessionRepository extends JpaRepository<ExtOrderRegisterSession, UUID> {

    /** The terminal's latest-opened session, whatever its state. */
    @NonNull
    Optional<ExtOrderRegisterSession> findFirstByTerminalIdOrderByOpenedAtDescSessionIdDesc(@NonNull String terminalId);
}
