package com.positivity.mcp.internal.repository;

import com.positivity.mcp.internal.entity.NltiRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NltiRequestRepository extends JpaRepository<NltiRequest, UUID> {

    List<NltiRequest> findBySessionId(UUID sessionId);
}
