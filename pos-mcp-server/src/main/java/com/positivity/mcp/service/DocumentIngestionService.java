package com.positivity.mcp.service;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;

public interface DocumentIngestionService {

  void ingestDocument(@NonNull String content, @NonNull Map<String, Object> metadata);

  void ingestDocuments(
      @NonNull List<String> contents,
      @NonNull List<Map<String, Object>> metadataList);
}
