package com.positivity.mcp.internal.orchestration;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.document.Document;

final class RagContextBuilder {

  private static final int MAX_CONTEXT_DOCS = 5;
  private static final int MAX_CONTEXT_CHARS = 4_000;

  private RagContextBuilder() {
  }

  static @NonNull String build(@NonNull List<Document> documents) {
    StringBuilder builder = new StringBuilder();
    int maxDocs = Math.min(MAX_CONTEXT_DOCS, documents.size());
    for (int index = 0; index < maxDocs; index++) {
      String text = documents.get(index).getText();
      if (text != null && !text.isBlank()) {
        if (!builder.isEmpty()) {
          builder.append(System.lineSeparator()).append(System.lineSeparator());
        }
        builder.append("[").append(index + 1).append("] ").append(text.trim());
        if (builder.length() >= MAX_CONTEXT_CHARS) {
          builder.setLength(MAX_CONTEXT_CHARS);
          break;
        }
      }
    }
    return builder.toString();
  }
}
