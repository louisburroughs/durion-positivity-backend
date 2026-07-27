package com.positivity.mcp.internal.orchestration.rag;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.document.Document;

@FunctionalInterface
public interface QueryDocumentRetriever {

    @NonNull
    List<Document> retrieve(@NonNull String queryText);
}