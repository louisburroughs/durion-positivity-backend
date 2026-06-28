package com.positivity.mcp.internal.repository;

import com.positivity.mcp.internal.entity.LlmApiConfig;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmApiConfigRepository extends JpaRepository<LlmApiConfig, UUID> {
    boolean existsByApiId(@NonNull String apiId);

    @NonNull
    Optional<LlmApiConfig> findByApiId(@NonNull String apiId);
}
