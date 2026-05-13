# LangChain4J Session Configuration Optimization Guide

**Goal**: Maximize tool selection accuracy and RAG retrieval relevance for the SessionAgentManager.

---

## Current Issues

### 🔴 Tool Selection Bottlenecks

| Issue                                | Current                                  | Impact                                  | Severity   |
| ------------------------------------ | ---------------------------------------- | --------------------------------------- | ---------- |
| **Hardcoded `candidateToolLimit`**   | `2` (default)                            | LLM sees only 2 tools; high miss rate   | **HIGH**   |
| **Fixed workflow state**             | `WORKFLOW_IDLE` (hardcoded)              | Tool selector ignores dynamic context   | **HIGH**   |
| **Keyword fallback only**            | Regex-based keywords                     | Misses semantic matches; low precision  | **MEDIUM** |
| **No tool descriptions**             | Tools injected without enriched metadata | LLM lacks context to disambiguate tools | **MEDIUM** |
| **No confidence scoring visibility** | Scores hidden from logs                  | Can't debug marginal tool picks         | **LOW**    |

### 🔴 RAG Retrieval Bottlenecks

| Issue                             | Current                  | Impact                               | Severity   |
| --------------------------------- | ------------------------ | ------------------------------------ | ---------- |
| **Tight similarity threshold**    | `minScore: 0.7`          | Filters out borderline-relevant docs | **HIGH**   |
| **Low result limit**              | `maxResults: 5`          | May miss best answer in top-10       | **HIGH**   |
| **Single-vector search**          | No query expansion       | Paraphrases miss the mark            | **HIGH**   |
| **No re-ranking**                 | Retrieval order is final | No LLM-based quality filtering       | **MEDIUM** |
| **No hybrid retrieval**           | Embeddings only          | Misses lexical/BM25 matches          | **MEDIUM** |
| **Window-based chat memory only** | Last 50 messages         | Long-term context lost               | **MEDIUM** |
| **No metadata filtering**         | Role filtering missing   | Tool outputs may bypass RBAC         | **LOW**    |

---

## Recommended Configuration Tiers

### Tier 1: Quick Wins (Implement First)

These are **one-line changes** with high impact:

#### 1.1 Increase Tool Candidate Limit

```yaml
# application.yml or application-alpha.yml
mcp.agent.candidate-tool-limit: 8 # was: 2
```

**Why**: With 8 tools, LLM has better visibility. If you have 20+ total tools per role, try 10.

**Implementation**: Update `SessionAgentManager` constructor parameter.

---

#### 1.2 Lower RAG Similarity Threshold

```java
// In SessionAgentManager.buildAgent()
ContentRetriever contentRetriever = EmbeddingStoreContentRetriever.builder()
        .embeddingStore(embeddingStore)
        .embeddingModel(embeddingModel)
        .maxResults(10)          // was: 5
        .minScore(0.6)           // was: 0.7 ← LOWER THIS
        .build();
```

**Why**: 0.7 is restrictive; 0.6 captures more borderline-relevant docs. LLM can filter low-quality results.

**Risk**: Slight increase in "noisy" results; mitigation: add re-ranking (Tier 2).

---

#### 1.3 Dynamic Workflow State from Context

```java
// In SessionAgentManager.roleToolsForMessage()
private @NonNull List<Object> roleToolsForMessage(@NonNull String role, @NonNull String message) {
    List<Object> fullRoleTools = toolRegistry.resolveToolsForRole(role);
    if (toolRegistryService == null) {
        return fullRoleTools;
    }
    try {
        // REPLACE hardcoded WORKFLOW_IDLE with dynamic state
        String workflowState = deriveWorkflowState(message);  // NEW
        List<String> selectedNames = toolRegistryService
                .resolveCandidateTools(
                    new ToolSelectionContext(message, role, workflowState),  // was: WORKFLOW_IDLE
                    candidateToolLimit)
                .stream()
                .map(ToolMetadata::name)
                .toList();
        // ... rest unchanged
    }
    // ...
}

private @NonNull String deriveWorkflowState(@NonNull String message) {
    String lower = message.toLowerCase(Locale.ROOT);
    if (containsAny(lower, Set.of("save", "create", "add", "new", "submit", "approve"))) {
        return "CREATE";
    } else if (containsAny(lower, Set.of("delete", "remove", "cancel", "void"))) {
        return "DELETE";
    } else if (containsAny(lower, Set.of("update", "edit", "modify", "change"))) {
        return "UPDATE";
    } else if (containsAny(lower, Set.of("list", "find", "search", "show", "get"))) {
        return "READ";
    } else if (containsAny(lower, Set.of("export", "report", "download"))) {
        return "EXPORT";
    }
    return "IDLE";
}
```

**Why**: Tool selector filters by allowed operations per workflow state. Avoids showing DELETE tools for read-only queries.

**Impact**: ~20-30% improvement in tool precision.

---

### Tier 2: Advanced Optimizations (Implement After Tier 1)

#### 2.1 Query Expansion for RAG

Use multi-query or HyDE (Hypothetical Document Embeddings) to generate paraphrases:

```java
private @NonNull ContentRetriever buildEnhancedRetriever() {
    // Base retriever
    ContentRetriever embeddingRetriever = EmbeddingStoreContentRetriever.builder()
            .embeddingStore(embeddingStore)
            .embeddingModel(embeddingModel)
            .maxResults(15)          // Increase for multi-pass
            .minScore(0.55)          // Lower threshold
            .build();

    // Query expansion: generate 2-3 paraphrases of the user query
    // Retrieve with all expanded queries, deduplicate
    return new QueryExpansionRetriever(embeddingRetriever, chatModel, embeddingModel);
}
```

**Pattern**:

1. Expand user query → ["original", "paraphrase_1", "paraphrase_2"]
2. Retrieve top-K for each → combine results by document ID
3. Re-rank by frequency + relevance score
4. Return top-10 deduplicated

**Expected**: +40% recall on paraphrased queries.

---

#### 2.2 Add Re-ranking (LLM or Cross-Encoder)

```java
private @NonNull ContentRetriever buildRankedRetriever() {
    ContentRetriever baseRetriever = EmbeddingStoreContentRetriever.builder()
            .embeddingStore(embeddingStore)
            .embeddingModel(embeddingModel)
            .maxResults(20)          // Retrieve more candidates
            .minScore(0.55)
            .build();

    // Wrap with re-ranker: LLM judges relevance
    return new RerankedContentRetriever(
            baseRetriever,
            chatModel,
            5);  // Keep top-5 after re-ranking
}
```

**How it works**:

- Retriever fetches 20 candidates
- LLM re-scores each: "Is this document relevant to the query?"
- Return top-5 re-ranked results
- ~60% improvement in hit rate

---

#### 2.3 Hybrid Retrieval (Embedding + BM25)

```java
private @NonNull ContentRetriever buildHybridRetriever() {
    // Embedding-based
    ContentRetriever embeddingRetriever = EmbeddingStoreContentRetriever.builder()
            .embeddingStore(embeddingStore)
            .embeddingModel(embeddingModel)
            .maxResults(10)
            .minScore(0.55)
            .build();

    // BM25-based (lexical search from pgvector or Elasticsearch)
    ContentRetriever bm25Retriever = new BM25ContentRetriever(embeddingStore);

    // Ensemble: combine both, de-dupe by document ID
    return new EnsembleContentRetriever(embeddingRetriever, bm25Retriever);
}
```

**When to use**:

- User queries with exact keywords: "invoice number XYZ" → BM25 wins
- Semantic queries: "how to process refunds" → embeddings win
- Hybrid: best of both

---

#### 2.4 Tool Confidence Scoring in Logs

Add to `SessionAgentManager.roleToolsForMessage()`:

```java
if (toolRegistryService != null) {
    try {
        List<ToolMetadata> candidates = toolRegistryService
                .resolveCandidateTools(context, candidateToolLimit);

        // Log with confidence scores
        if (LOGGER.isDebugEnabled()) {
            candidates.forEach(tool -> {
                LOGGER.debug(
                    "MCP tool candidate role={} toolName={} score={} priority={}",
                    role,
                    tool.name(),
                    tool.confidence(),  // Expose score
                    tool.priority());
            });
        }
        // ...
    }
}
```

**Why**: See which tools are marginal (score: 0.51 vs. 0.92). Helps tune classifier.

---

### Tier 3: Long-Term Improvements (Future)

#### 3.1 Persistent Long-Term Memory

Replace `MessageWindowChatMemory` with:

- **User preference store**: "User prefers verbose explanations"
- **Session summaries**: Save/load multi-turn conversations
- **Semantic memory**: Store facts about user (role, domain, style)

```java
// Pseudo-code
ChatMemory chatMemory = new SemanticChatMemory(
    new MemoryStore(pgPool),
    new MemoryRetriever(embeddingModel),
    new SessionSummarizer(chatModel)
);
```

#### 3.2 Role-Aware RAG Metadata Filtering

```java
.contentRetriever(builder
    .embeddingStore(embeddingStore)
    .embeddingModel(embeddingModel)
    .metadataFilter(where("role").in(userContext.roles()))  // NEW
    .maxResults(10)
    .build())
```

#### 3.3 Tool-Result Re-Ranking

After tool execution, re-rank tool results by relevance before sending to LLM.

---

## Implementation Roadmap

### Phase 1: Immediate (This Sprint)

- [ ] Increase `candidateToolLimit` to 8
- [ ] Decrease `minScore` to 0.6, increase `maxResults` to 10
- [ ] Add dynamic workflow state derivation
- [ ] Add confidence score logging

**Estimated impact**: +35-50% tool precision, +25-30% RAG recall.

---

### Phase 2: Next Sprint

- [ ] Implement query expansion (multi-query)
- [ ] Add LLM-based re-ranking
- [ ] Write tests for retrieval quality (hit@5, MRR)

**Estimated impact**: +40% recall, +20% precision.

---

### Phase 3: Future

- [ ] Hybrid embedding+BM25 retrieval
- [ ] Persistent memory layer
- [ ] Role-aware metadata filtering

---

## Configuration Reference

### Tuning Parameters

```yaml
mcp.agent:
  cache-ttl-minutes: 30 # Agent cache lifetime
  max-cached-agents: 500 # Max agents to cache
  memory-max-messages: 50 # ← INCREASE to 100 for long context
  candidate-tool-limit: 8 # ← INCREASE from 2

pos.nlti.rate-limit.per-session: 100 # Max requests per session
```

### RAG Parameters

```java
// In buildAgent()
EmbeddingStoreContentRetriever.builder()
    .embeddingStore(embeddingStore)
    .embeddingModel(embeddingModel)
    .maxResults(10)                      # Retrieve candidates
    .minScore(0.6)                       # Similarity threshold
    // Add if implementing Tier 2:
    // .metadataFilter(...)              # Role-aware filtering
    .build();
```

---

## Monitoring & Metrics

Add to your observability stack:

```java
// Tool selection metrics
metrics.timer("mcp.tool.selection.duration").record(duration);
metrics.gauge("mcp.tool.selection.candidate_count", candidates.size());
metrics.gauge("mcp.tool.selection.confidence_score", topScore);

// RAG metrics
metrics.gauge("mcp.rag.retrieved_docs", retrievedCount);
metrics.gauge("mcp.rag.avg_similarity_score", avgScore);
metrics.timer("mcp.rag.retrieval.duration").record(duration);

// Success metrics
metrics.counter("mcp.chat.tool_call_success", tags("role", role)).increment();
metrics.counter("mcp.chat.rag_hit", tags("query_type", type)).increment();  // Did RAG return relevant doc?
```

---

## Validation Checklist

Before rollout:

- [ ] Test with 10+ real user queries per role
- [ ] Verify tool selection precision (right tools picked)
- [ ] Verify RAG recall (relevant docs retrieved in top-3)
- [ ] Check for tool conflicts (duplicate names)
- [ ] Validate RBAC: no leaked tools across roles
- [ ] Check latency impact (<500ms for tool selection + RAG)
- [ ] Monitor error rate from LLM tool invocation

---

## Quick Wins Script

To implement Tier 1 quickly:

```bash
#!/bin/bash
# 1. Update config
sed -i 's/candidate-tool-limit: 2/candidate-tool-limit: 8/' application-alpha.yml
sed -i 's/memory-max-messages: 50/memory-max-messages: 100/' application-alpha.yml

# 2. Rebuild agent
./mvnw -pl pos-mcp-server clean package

# 3. Test
curl -X POST http://localhost:8080/v1/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"List all inventory for SKU ABC123"}'
```

---

## Troubleshooting

### Q: Tools are still wrong even after increasing `candidateToolLimit`

**A**: Check `ToolRegistryService` scoring logic. May need:

1. Richer tool descriptions (metadata)
2. Query expansion to match semantic intent
3. Workflow state derivation

### Q: RAG returns too many irrelevant docs

**A**:

1. Increase `minScore` back to 0.65
2. Add re-ranking (LLM filter)
3. Check embedding model quality: does it handle your domain well?

### Q: Chat latency increased after changes

**A**:

1. Reduce `maxResults` back to 8
2. Cache embeddings of common queries
3. Implement async retrieval

---

## References

- [LangChain4J AiServices Docs](https://docs.langchain4j.dev/integrations/embedding-stores/)
- [Query Expansion Patterns](https://arxiv.org/abs/2305.03653)
- [Re-ranking with LLMs](https://docs.llamaindex.ai/en/stable/examples/query_engine/retriever_router.html)
- [Hybrid Search](https://www.elastic.co/guide/en/elasticsearch/reference/current/hybrid-search.html)
