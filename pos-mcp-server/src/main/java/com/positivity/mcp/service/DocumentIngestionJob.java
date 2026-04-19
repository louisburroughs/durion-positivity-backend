package com.positivity.mcp.service;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record DocumentIngestionJob(
        @NonNull UUID jobId,
        @NonNull String documentId,
        @NonNull DocumentIngestionJobStatus status,
        @NonNull OffsetDateTime createdAt,
        @NonNull OffsetDateTime updatedAt,
        @Nullable OffsetDateTime startedAt,
        @Nullable OffsetDateTime completedAt,
        @Nullable Integer chunkCount,
        @Nullable String errorMessage) {}
