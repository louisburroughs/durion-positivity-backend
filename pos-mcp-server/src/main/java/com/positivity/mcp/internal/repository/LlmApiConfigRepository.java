package com.positivity.mcp.internal.repository;

import com.positivity.mcp.internal.entity.LlmApiConfig;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LlmApiConfigRepository extends JpaRepository<LlmApiConfig, UUID> {
    boolean existsByApiId(@NonNull String apiId);
    @NonNull Optional<LlmApiConfig> findByApiId(@NonNull String apiId);
}

