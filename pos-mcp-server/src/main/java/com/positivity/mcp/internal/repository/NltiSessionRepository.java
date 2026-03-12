package com.positivity.mcp.internal.repository;

import com.positivity.mcp.internal.entity.NltiSession;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NltiSessionRepository extends JpaRepository<NltiSession, UUID> {

    Optional<NltiSession> findByIdAndSubjectId(UUID id, String subjectId);
}
