# Tool Registry Implementation Guide

Reference implementation for `ToolRegistryService` in a Spring Boot MCP server.

## Overview

This implementation demonstrates a scalable tool selection strategy using:

- PostgreSQL with pgvector for vector similarity search
- Role-based access control filtering
- Workflow state gating
- Embedding-based top-K selection
- Deterministic scoring algorithm

**Assumption**: You expose ~15–25 facade tools, not 200 raw APIs.

---

## 1. Domain Models

### ToolMetadata

```java
public record ToolMetadata(
        UUID id,
        String name,
        String displayName,
        String description,
        String domain,
        double priority,
        String costLevel,
        int avgLatencyMs,
        boolean enabled,
        String handlerBean
) {}
```

### ToolSelectionContext

```java
public record ToolSelectionContext(
        String userInput,
        String role,
        String workflowState
) {}
```

---

## 2. Repository Layer

### ToolMetadataRepository Interface

```java
@Repository
public interface ToolMetadataRepository {

    List<ToolMetadata> findEnabledByRoleAndWorkflow(
            String role,
            String workflowState
    );

    List<ToolMetadata> findTopKByEmbedding(
            float[] embedding,
            int limit
    );
}
```

### JDBC Implementation (pgvector)

```java
@Repository
@RequiredArgsConstructor
public class ToolMetadataRepositoryImpl implements ToolMetadataRepository {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<ToolMetadata> findEnabledByRoleAndWorkflow(
            String role,
            String workflowState
    ) {
        String sql = """
            SELECT t.id, t.name, t.display_name, t.description,
                   t.domain, t.priority, t.cost_level,
                   t.avg_latency_ms, t.enabled, t.handler_bean
            FROM mcp_tool t
            JOIN mcp_tool_role tr ON t.id = tr.tool_id
            JOIN mcp_role r ON tr.role_id = r.id
            JOIN mcp_tool_workflow tw ON t.id = tw.tool_id
            JOIN mcp_workflow_state ws ON tw.workflow_state_id = ws.id
            WHERE t.enabled = true
              AND r.name = ?
              AND ws.name = ?
        """;

        return jdbcTemplate.query(sql, this::mapRow, role, workflowState);
    }

    @Override
    public List<ToolMetadata> findTopKByEmbedding(
            float[] embedding,
            int limit
    ) {
        String sql = """
            SELECT id, name, display_name, description,
                   domain, priority, cost_level,
                   avg_latency_ms, enabled, handler_bean
            FROM mcp_tool
            WHERE enabled = true
            ORDER BY embedding <=> ? 
            LIMIT ?
        """;

        return jdbcTemplate.query(sql, this::mapRow, embedding, limit);
    }

    private ToolMetadata mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ToolMetadata(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("display_name"),
                rs.getString("description"),
                rs.getString("domain"),
                rs.getDouble("priority"),
                rs.getString("cost_level"),
                rs.getInt("avg_latency_ms"),
                rs.getBoolean("enabled"),
                rs.getString("handler_bean")
        );
    }
}
```

---

## 3. Embedding Service

Abstract the embedding model behind a service interface.

### Interface

```java
public interface EmbeddingService {
    float[] embed(String text);
}
```

### Example Implementation

```java
@Service
public class OpenAIEmbeddingService implements EmbeddingService {

    @Override
    public float[] embed(String text) {
        // Call embedding model API, return 1536-dim vector
        return new float[1536];
    }
}
```

---

## 4. Deterministic Scoring Strategy

Combine multiple factors to score tool candidates:

- **Semantic similarity** (via vector ranking order)
- **Tool priority** (business-defined weight)
- **Latency penalty** (penalize slow tools)
- **Cost penalty** (penalize expensive operations)

```java
@Component
public class ToolScorer {

    public double score(
            ToolMetadata tool,
            int rankPosition
    ) {
        double semanticScore = 1.0 / (rankPosition + 1);

        double priorityBoost = tool.priority();

        double latencyPenalty =
                Math.min(tool.avgLatencyMs() / 1000.0, 1.0) * 0.2;

        double costPenalty = switch (tool.costLevel()) {
            case "high" -> 0.2;
            case "medium" -> 0.1;
            default -> 0.0;
        };

        return semanticScore
                + priorityBoost
                - latencyPenalty
                - costPenalty;
    }
}
```

---

## 5. ToolRegistryService (Core)

```java
@Service
@RequiredArgsConstructor
public class ToolRegistryService {

    private final ToolMetadataRepository repository;
    private final EmbeddingService embeddingService;
    private final ToolScorer scorer;

    public List<ToolMetadata> resolveCandidateTools(
            ToolSelectionContext context,
            int topK
    ) {

        // 1. Role + workflow prefilter
        List<ToolMetadata> gatedTools =
                repository.findEnabledByRoleAndWorkflow(
                        context.role(),
                        context.workflowState()
                );

        if (gatedTools.isEmpty()) {
            return List.of();
        }

        // 2. Compute embedding for user input
        float[] embedding =
                embeddingService.embed(context.userInput());

        // 3. Get semantic top-K from DB
        List<ToolMetadata> semanticCandidates =
                repository.findTopKByEmbedding(embedding, 10);

        // 4. Intersect with gated tools
        Map<UUID, ToolMetadata> gatedMap =
                gatedTools.stream()
                        .collect(Collectors.toMap(
                                ToolMetadata::id,
                                t -> t
                        ));

        List<ToolMetadata> filtered =
                semanticCandidates.stream()
                        .filter(t -> gatedMap.containsKey(t.id()))
                        .toList();

        // 5. Deterministic scoring
        AtomicInteger rank = new AtomicInteger(0);

        return filtered.stream()
                .sorted((a, b) -> {
                    int posA = rank.getAndIncrement();
                    int posB = rank.getAndIncrement();

                    double scoreA = scorer.score(a, posA);
                    double scoreB = scorer.score(b, posB);

                    return Double.compare(scoreB, scoreA);
                })
                .limit(topK)
                .toList();
    }
}
```

---

## 6. MCP Integration Example

```java
public List<McpTool> buildMcpToolContext(
        ToolSelectionContext context
) {
    List<ToolMetadata> candidates =
            resolveCandidateTools(context, 5);

    return candidates.stream()
            .map(this::toMcpTool)
            .toList();
}
```

---

## 7. Runtime Flow

```text
User Input
   ↓
Intent Resolver (optional)
   ↓
ToolRegistryService.resolveCandidateTools()
   ↓
Return 3–5 tools
   ↓
MCP chooses one
   ↓
Facade bean invoked
```

---

## Why This Scales

Starting with 200 raw APIs, the strategy progressively filters down to a manageable set:

| Step | Count | Method |
| --- | --- | --- |
| Raw APIs | 200 | Initial surface |
| Facade tools | 20 | Logical grouping |
| Role filter | 8–12 | User permissions |
| Workflow gate | 5–8 | Current workflow state |
| Embedding top-K | 3–5 | Semantic similarity |
| Deterministic scoring | 3–5 | Final ranking |

**Result**: The LLM never evaluates 200 tools—only 3–5 highly relevant candidates.