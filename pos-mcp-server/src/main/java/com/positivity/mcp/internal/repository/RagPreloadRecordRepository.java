package com.positivity.mcp.internal.repository;

import com.positivity.mcp.internal.entity.RagPreloadRecord;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RagPreloadRecordRepository extends JpaRepository<RagPreloadRecord, UUID> {

  @NonNull
  Optional<RagPreloadRecord> findFirstByDocumentIdOrderByLoadedAtDesc(@NonNull String documentId);
}