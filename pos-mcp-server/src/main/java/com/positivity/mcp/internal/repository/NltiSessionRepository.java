package com.positivity.mcp.internal.repository;

import com.positivity.mcp.internal.entity.NltiSession;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NltiSessionRepository extends JpaRepository<NltiSession, UUID> {

    Optional<NltiSession> findByIdAndSubjectId(UUID id, String subjectId);

    /**
     * The subject's most-recently-updated session — treated as their active session when the
     * session-less chat path resolves the persisted workflow state (#778).
     */
    Optional<NltiSession> findFirstBySubjectIdOrderByUpdatedAtDesc(String subjectId);
}
