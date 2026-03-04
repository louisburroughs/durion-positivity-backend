Below is a **production-oriented audit + feedback loop design** for automatic priority tuning of MCP tools in a Spring MVC architecture.

The goal:

* Log every tool decision
* Measure success, latency, overrides
* Periodically compute performance scores
* Automatically adjust `mcp_tool.priority`

---

# 1. Audit Table (Invocation Log)

```sql
CREATE TABLE mcp_tool_invocation_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    tool_id UUID NOT NULL REFERENCES mcp_tool(id),

    user_id VARCHAR(100),
    session_id VARCHAR(100),

    intent VARCHAR(100),
    workflow_state VARCHAR(100),

    semantic_rank INT,          -- rank from embedding search
    final_score NUMERIC(6,4),   -- deterministic score at selection time

    selected BOOLEAN,           -- was it chosen by MCP
    success BOOLEAN,            -- business success
    fallback_invoked BOOLEAN,   -- did we switch tools

    execution_time_ms INT,
    error_type VARCHAR(100),

    created_at TIMESTAMP DEFAULT now()
);
```

This enables:

* Tool confusion detection
* Latency comparison
* Success-rate tracking
* Over-selection detection

---

# 2. Audit Logging Service

```java
@Service
@RequiredArgsConstructor
public class ToolAuditService {

    private final JdbcTemplate jdbcTemplate;

    public void logInvocation(
            UUID toolId,
            String userId,
            String sessionId,
            String intent,
            String workflowState,
            int semanticRank,
            double finalScore,
            boolean selected,
            boolean success,
            boolean fallbackInvoked,
            int executionTimeMs,
            String errorType
    ) {

        String sql = """
            INSERT INTO mcp_tool_invocation_log (
                tool_id, user_id, session_id,
                intent, workflow_state,
                semantic_rank, final_score,
                selected, success, fallback_invoked,
                execution_time_ms, error_type
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

        jdbcTemplate.update(sql,
                toolId, userId, sessionId,
                intent, workflowState,
                semanticRank, finalScore,
                selected, success, fallbackInvoked,
                executionTimeMs, errorType
        );
    }
}
```

Invoke this immediately after tool execution.

---

# 3. Aggregated Performance View

Create a materialized view or query:

```sql
SELECT
    tool_id,
    COUNT(*) AS total_calls,
    AVG(CASE WHEN success THEN 1 ELSE 0 END) AS success_rate,
    AVG(execution_time_ms) AS avg_latency,
    AVG(CASE WHEN fallback_invoked THEN 1 ELSE 0 END) AS fallback_rate
FROM mcp_tool_invocation_log
WHERE created_at > now() - interval '7 days'
GROUP BY tool_id;
```

---

# 4. Priority Adjustment Formula

We want to increase priority when:

* Success rate is high
* Latency is low
* Fallback rate is low

Example scoring formula:

```text
performance_score =
    (success_rate * 0.6)
  + ((1 - normalized_latency) * 0.3)
  - (fallback_rate * 0.2)
```

Normalize latency:

```text
normalized_latency = min(avg_latency / 2000ms, 1.0)
```

Clamp final priority between 0.1 and 1.0.

---

# 5. Priority Tuning Service

Scheduled daily.

```java
@Service
@RequiredArgsConstructor
public class ToolPriorityTuningService {

    private final JdbcTemplate jdbcTemplate;

    @Scheduled(cron = "0 0 2 * * *") // 2 AM daily
    public void adjustPriorities() {

        String statsQuery = """
            SELECT
                tool_id,
                COUNT(*) AS total_calls,
                AVG(CASE WHEN success THEN 1 ELSE 0 END) AS success_rate,
                AVG(execution_time_ms) AS avg_latency,
                AVG(CASE WHEN fallback_invoked THEN 1 ELSE 0 END) AS fallback_rate
            FROM mcp_tool_invocation_log
            WHERE created_at > now() - interval '7 days'
            GROUP BY tool_id
            HAVING COUNT(*) > 20
        """;

        jdbcTemplate.query(statsQuery, rs -> {

            UUID toolId = rs.getObject("tool_id", UUID.class);
            double successRate = rs.getDouble("success_rate");
            double avgLatency = rs.getDouble("avg_latency");
            double fallbackRate = rs.getDouble("fallback_rate");

            double normalizedLatency = Math.min(avgLatency / 2000.0, 1.0);

            double performanceScore =
                    (successRate * 0.6)
                  + ((1 - normalizedLatency) * 0.3)
                  - (fallbackRate * 0.2);

            double newPriority = clamp(performanceScore, 0.1, 1.0);

            updatePriority(toolId, newPriority);
        });
    }

    private void updatePriority(UUID toolId, double newPriority) {
        jdbcTemplate.update(
                "UPDATE mcp_tool SET priority = ?, updated_at = now() WHERE id = ?",
                newPriority, toolId
        );
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
```

---

# 6. Optional: Decay / Stability Mechanism

To prevent oscillation:

Use exponential smoothing:

```java
double adjustedPriority =
    (oldPriority * 0.7) + (newPriority * 0.3);
```

Fetch current priority before update.

---

# 7. Detect Tool Confusion

You can detect overlapping tools:

```sql
SELECT tool_id,
       COUNT(*) FILTER (WHERE fallback_invoked = true)::float /
       COUNT(*) AS fallback_ratio
FROM mcp_tool_invocation_log
GROUP BY tool_id
HAVING COUNT(*) > 50
ORDER BY fallback_ratio DESC;
```

High fallback ratio → tool likely ambiguous or poorly described.

---

# 8. Closed-Loop Flow

```text
Tool Selection
    ↓
Execution
    ↓
Audit Log Entry
    ↓
Daily Aggregation
    ↓
Priority Adjustment
    ↓
Improved Future Ranking
```

This creates **adaptive ranking without retraining embeddings**.

---

# 9. What This Achieves

Over time:

* High-performing tools rise
* Slow or error-prone tools decay
* Overlapping tools become visible
* Priority tuning becomes data-driven
* Manual rebalancing becomes rare

---

# 10. Enterprise Hardening Options

For more mature systems:

* Separate training dataset table
* A/B priority experiments
* Multi-armed bandit algorithm instead of static formula
* Confidence weighting based on call volume
* Per-role priority adjustments

---
