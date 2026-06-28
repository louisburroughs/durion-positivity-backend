package com.positivity.mcp.internal.repository;

import com.positivity.mcp.internal.entity.SystemPrompt;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SystemPromptRepository extends JpaRepository<SystemPrompt, UUID> {
    @NonNull
    Optional<SystemPrompt> findByName(@NonNull String name);

    boolean existsByName(@NonNull String name);
}
