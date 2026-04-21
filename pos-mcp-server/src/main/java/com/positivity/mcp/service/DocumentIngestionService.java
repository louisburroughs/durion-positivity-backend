package com.positivity.mcp.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface DocumentIngestionService {

    @NonNull
    DocumentIngestionJob submitDocument(@NonNull String content, @NonNull Map<String, Object> metadata);

    @NonNull
    Optional<DocumentIngestionJob> getIngestionJob(@NonNull UUID jobId);

    void ingestDocument(@NonNull String content, @NonNull Map<String, Object> metadata);

    void ingestDocuments(@NonNull List<String> contents, @NonNull List<Map<String, Object>> metadataList);
}
