# MCP Tool Interface design

## Tool List

- InventoryTool
- OrderTool
- CustomerTool
- PricingTool
- ReportingTool
- AdminTool
- WorkorderTool
- ShopManagerTool
- AccountingTool
- CatalogTool
- EventsTool
- InvoiceTool
- LocationTool
- HRTool
- TaxTool
- VehicleTool

## Tool Facades

```java
@Component
public class InventoryToolFacade {

    private final InventoryService inventoryService;

    public InventoryToolFacade(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    public Object handle(InventoryCommand command) {
        return switch (command.operation()) {
            case CHECK_STOCK -> inventoryService.checkStock(command.sku());
            case ADJUST_STOCK -> inventoryService.adjustStock(command);
            case GET_LOCATION -> inventoryService.getLocation(command.sku());
        };
    }
}
```

## Intent Classification Layer

```text
"Create PO for NAPA" → ORDER domain
"Check tire stock in Charlotte" → INVENTORY domain
```

```java
@Service
public class IntentRouter {

    public Domain resolve(String userInput) {
        // rules + embedding similarity
    }
}
```

## Embedding-Based Tool Candidate Selection

- Store tool metadata in a DB table:

| tool_name | domain | embedding | priority |
| --- | --- | --- | --- |

At runtime:

1. Embed user query
1. Run vector similarity (pgvector, OpenSearch, or Pinecone)
1. Return top 3–5 candidates
1. Pass only those into MCP context

Spring stack options:

1. PostgreSQL + pgvector -*
1. OpenSearch
1. Redis with vector module

This prevents scanning 200 tool descriptions every call.

## Role-Based Dynamic Tool Registration

Since you are in a POS environment:

| Role | Tools Visible |
| --- | --- |
| Cashier | Sales, Payments, Inventory Lookup |
| Manager | Reporting, Adjustments |
| Admin | Configuration |
| Supplier | PO + ASN |

At session start:

```java
@Bean
@Scope("request")
public ToolRegistry toolRegistry(UserContext context) {
    return registryFactory.createForRole(context.role());
}
```

## State-Aware Tool Gating

Maintain a conversation workflow state:

```java
enum WorkflowState {
    IDLE,
    CREATING_PO,
    RECEIVING_ASN,
    INVENTORY_RECON
}
```

If in CREATING_PO, only allow:

- validateVendor
- addLineItem
- finalizePO

This prevents cross-domain drift and reduces ambiguity.

## Observability + Tool Metrics (Critical at Scale)

Instrument:

- Tool selection frequency
- Tool misfires
- Latency per tool
- LLM confusion rate (tool changed mid-flow)

Use:

- Micrometer
- Prometheus
- OpenTelemetry

Log:

- user_intent
- candidate_tools
- selected_tool
- execution_time
- result_confidence

## Cost-Aware Fallback Strategy

Implement a deterministic-first strategy:

1. Try rules engine
1. Try cached answer
1. Then call MCP tool

In Spring:

- Caffeine caching
- Optional Drools or simple rule engine

Do not always invoke the LLM, if not required.

## Clean Scalable Design Summary

For a Spring-based MCP server handling 200 APIs:

### Reduce surface area

200 APIs → 20 facade tools

### Add structured routing

Intent → Domain → Candidate tools → MCP

### Dynamically register

Based on:

- Role
- Workflow state
- Session type

### Use embeddings for ranking

#### Never let the LLM “scan” 200 tools blindly

## Sample Schema

### Core Tool Registry Table

```sql
CREATE TABLE mcp_tool (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- External identity
    name VARCHAR(100) NOT NULL UNIQUE,
    display_name VARCHAR(150) NOT NULL,
    description TEXT NOT NULL,

    -- Domain classification
    domain VARCHAR(100) NOT NULL,
    subdomain VARCHAR(100),

    -- Routing & selection metadata
    priority NUMERIC(3,2) DEFAULT 0.5,   -- 0.0 to 1.0
    cost_level VARCHAR(20) DEFAULT 'medium',  -- low | medium | high
    avg_latency_ms INT DEFAULT 0,

    -- Safety / gating
    requires_auth BOOLEAN DEFAULT TRUE,
    idempotent BOOLEAN DEFAULT FALSE,

    -- MCP behavior
    is_facade BOOLEAN DEFAULT TRUE,
    handler_bean VARCHAR(200) NOT NULL,

    -- Embedding vector (pgvector)
    embedding VECTOR(1536),

    -- Lifecycle
    enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT now(),
    updated_at TIMESTAMP DEFAULT now()
);
```

### Tool Parameters

```sql
CREATE TABLE mcp_tool_parameter (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tool_id UUID REFERENCES mcp_tool(id) ON DELETE CASCADE,

    name VARCHAR(100) NOT NULL,
    type VARCHAR(50) NOT NULL,           -- string, number, boolean, enum, object
    required BOOLEAN DEFAULT FALSE,
    description TEXT,
    enum_values TEXT[],                  -- if type = enum

    created_at TIMESTAMP DEFAULT now()
);
```

### Role-Based Visibility

```sql
CREATE TABLE mcp_role (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL UNIQUE
);

CREATE TABLE mcp_tool_role (
    tool_id UUID REFERENCES mcp_tool(id) ON DELETE CASCADE,
    role_id UUID REFERENCES mcp_role(id) ON DELETE CASCADE,
    PRIMARY KEY (tool_id, role_id)
);
```

This allows:

- Cashier → limited tools
- Manager → extended tools
- Admin → full domain set

### Workflow / State Gating

```sql
CREATE TABLE mcp_workflow_state (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL UNIQUE
);

CREATE TABLE mcp_tool_workflow (
    tool_id UUID REFERENCES mcp_tool(id) ON DELETE CASCADE,
    workflow_state_id UUID REFERENCES mcp_workflow_state(id) ON DELETE CASCADE,
    PRIMARY KEY (tool_id, workflow_state_id)
);
```

This enables:

- Only PO tools during PO creation
- Only inventory tools during reconciliation

### Intent Mapping (Optional but Recommended)

For deterministic pre-filtering:

```sql
CREATE TABLE mcp_intent (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL UNIQUE,
    domain VARCHAR(100) NOT NULL
);

CREATE TABLE mcp_intent_tool (
    intent_id UUID REFERENCES mcp_intent(id) ON DELETE CASCADE,
    tool_id UUID REFERENCES mcp_tool(id) ON DELETE CASCADE,
    PRIMARY KEY (intent_id, tool_id)
);
```

You can:

- Run classifier → resolve intent
- Only load tools mapped to that intent

### Observability & Metrics (Highly Recommended)

```sql
CREATE TABLE mcp_tool_invocation_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tool_id UUID REFERENCES mcp_tool(id),

    user_id VARCHAR(100),
    session_id VARCHAR(100),

    intent VARCHAR(100),
    workflow_state VARCHAR(100),

    selected BOOLEAN,
    success BOOLEAN,

    execution_time_ms INT,
    error_message TEXT,

    created_at TIMESTAMP DEFAULT now()
);
```

This enables:

- Tool confusion detection
- Latency optimization
- Consolidation analysis
- Misfire tracking

### Vector Index for Fast Candidate Selection

```sql
CREATE INDEX mcp_tool_embedding_idx
ON mcp_tool
USING ivfflat (embedding vector_cosine_ops)
WITH (lists = 100);
```

Runtime query:

```sql
SELECT *
FROM mcp_tool
WHERE enabled = true
ORDER BY embedding <=> :query_embedding
LIMIT 5;
```

### Sample Tool Record

```sql
INSERT INTO mcp_tool (
    name,
    display_name,
    description,
    domain,
    priority,
    cost_level,
    handler_bean
)
VALUES (
    'inventory_tool',
    'Inventory Tool',
    'Handles stock lookup, stock adjustment, and location queries.',
    'inventory',
    0.8,
    'low',
    'inventoryToolFacade'
);
```

### Recommended Spring Structure

```plain text
com.company.mcp
  ├── registry
  │     ├── ToolRegistryService
  │     ├── ToolMetadataRepository
  │     └── EmbeddingSearchService
  ├── routing
  │     ├── IntentClassifier
  │     ├── RoleToolFilter
  │     └── WorkflowGate
  ├── facade
  │     ├── InventoryToolFacade
  │     ├── OrderToolFacade
  │     └── ...
  └── observability
        └── ToolMetricsService
```

### How This Scales

With 200 APIs:

- Expose 15–25 facade tools
- Use role + workflow filters → reduce to 5–10
- Use embedding ranking → reduce to 3–5
- Let MCP decide among a small set

This avoids quadratic tool confusion growth.
