package com.positivity.mcp.internal.repository;

import com.positivity.mcp.internal.entity.DocumentIngestionJobEntity;
import com.positivity.mcp.internal.entity.DocumentIngestionJobState;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentIngestionJobRepository extends JpaRepository<DocumentIngestionJobEntity, UUID> {

    @NonNull
    List<DocumentIngestionJobEntity> findByStatusIn(@NonNull Collection<DocumentIngestionJobState> statuses);
}
