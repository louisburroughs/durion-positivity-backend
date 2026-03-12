package com.positivity.mcp.internal.repository;

import com.positivity.mcp.internal.entity.NltiIntent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NltiIntentRepository extends JpaRepository<NltiIntent, UUID> {

    List<NltiIntent> findBySessionIdOrderByCreatedAtDesc(UUID sessionId);

    Optional<NltiIntent> findByRequestId(UUID requestId);
}
