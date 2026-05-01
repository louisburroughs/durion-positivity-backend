package com.positivity.mcp.internal.repository;

import com.positivity.mcp.internal.entity.RagPreloadRecord;
import com.positivity.mcp.internal.enums.RagPreloadStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RagPreloadRecordRepository extends JpaRepository<RagPreloadRecord, UUID> {

  @NonNull
  Optional<RagPreloadRecord> findFirstByDocumentIdAndStatusOrderByLoadedAtDesc(
      @NonNull String documentId, @NonNull RagPreloadStatus status);

  @NonNull
  List<RagPreloadRecord> findAllByStatus(@NonNull RagPreloadStatus status);
}