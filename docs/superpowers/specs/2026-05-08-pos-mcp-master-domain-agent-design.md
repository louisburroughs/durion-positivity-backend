# pos-mcp-server master-agent and domain-agent design

## Problem statement

`pos-mcp-server` currently centers its orchestration around one large assistant that can see many business facade tools at once. That keeps the initial implementation simple, but it blurs domain boundaries, grows prompt and tool surface area, and encourages duplicated orchestration logic between synchronous and streaming paths.

The target design is a cleaner architecture:

- one **master agent** owns the user conversation and orchestration
- one **domain agent per current MCP business domain** owns domain-specific reasoning
- each current facade tool's API operations become the **tool surface inside that domain agent**
- general knowledge tools remain attached to the **master**
- synchronous and streaming chat share one orchestration core and differ only in delivery mode

This design favors clarity and a clean end state over migration safety.

## Goals

- Preserve one user-facing conversation and one session memory owner
- Isolate business reasoning by domain
- Keep general-purpose tools out of domain agents
- Allow a single request to coordinate multiple domain agents
- Remove avoidable duplication between `SessionAgentManager` and `StreamingSessionAgentManager`
- Keep orchestration contracts explicit and observable
- Give the master and each domain agent their own RAG repository boundary

## Non-goals

- Backward-compatible migration layers
- Transitional dual architecture
- Peer-to-peer collaboration between domain agents
- Per-domain long-lived chat memory

## Target architecture

### 1. Master agent

The master agent is the only long-lived conversational agent in the system.

Responsibilities:

- own session memory and user context
- classify and decompose incoming requests
- decide whether to use master-level shared tools, domain agents, or both
- invoke one or more domain agents for a single request
- merge structured domain-agent results into one user-facing answer
- preserve partial results when one domain fails

The master is the only component that should synthesize the final end-user response.

### 2. Domain agents

There is one domain agent per current business domain/facade boundary, such as:

- `InventoryAgent`
- `OrderAgent`
- `PricingAgent`
- `CustomerAgent`
- `CatalogAgent`
- `InvoiceAgent`
- `VehicleAgent`
- `WorkorderAgent`
- `ReportingAgent`
- `AdminAgent`
- `EventsAgent`
- `TaxAgent`
- `HrAgent`
- `LocationAgent`
- `ShopManagerAgent`

Responsibilities:

- accept a focused task from the master
- reason only within their domain boundary
- use only their domain-local tools
- return structured results to the master

Domain agents are **stateless specialists**. They do not own long-lived independent session memory.

### 3. Tool ownership model

#### Master-level shared tools

General knowledge and cross-domain enrichment tools belong to the master, not to domain agents.

Examples:

- `ExaWebSearchTool`
- weather lookup
- stock ticker / market lookup
- future tools that do not belong to one POS business domain

Requests that only need shared tools should stay entirely in the master layer.

#### Domain-local tools

Business APIs belong only to their owning domain agent.

Examples:

- inventory APIs stay in `InventoryAgent`
- order APIs stay in `OrderAgent`
- pricing APIs stay in `PricingAgent`

Current facade tool methods become the direct tool surface inside the owning domain agent.

### 4. RAG ownership model

RAG should follow the same ownership boundaries as orchestration and tools.

#### Master RAG repository

The master should have its own RAG repository for content that is:

- global across the system
- cross-domain
- related to orchestration behavior, policies, or shared operating knowledge
- useful before the master decides which domain agents to invoke

The master RAG repository should not become a dumping ground for domain-specific operational knowledge that belongs to one business area.

#### Domain-agent RAG repositories

Each domain agent should have access to its own domain-specific RAG repository.

Examples:

- inventory documentation and operational knowledge live in the inventory RAG repository
- pricing documentation and pricing-specific knowledge live in the pricing RAG repository
- workorder guidance and workorder-specific knowledge live in the workorder RAG repository

This keeps retrieval focused and avoids domain agents pulling irrelevant context from unrelated business areas.

#### RAG usage rule

- the master retrieves from the master RAG repository
- a domain agent retrieves from its own domain RAG repository
- domain agents should not read from each other's RAG repositories
- the master should not bypass a domain agent by directly querying that domain's private RAG repository during normal orchestration

That preserves the same clean boundary across prompts, tools, and retrieved knowledge.

### 5. Request execution flow

The target execution shape is:

1. Master receives the user request
2. Master classifies the request
3. Master decides whether shared tools are needed
4. Master selects one or more domain agents
5. Each selected domain agent uses only its own tools
6. Domain agents return structured results
7. Master merges results and produces one final response

Multi-domain requests are a first-class path. The master may coordinate multiple domain agents within one user request.

Domain agents do **not** call each other directly. Cross-domain orchestration always returns through the master.

## Domain-agent contract

Each domain agent should return a structured result object instead of a conversational end-user answer.

Recommended result shape:

- `status`
- `summary`
- `domainFacts`
- `toolCalls`
- `errors`
- `followUpQuestions`

This gives the master enough structure to:

- synthesize a final answer
- decide whether another domain agent is needed
- preserve partial results
- surface targeted follow-up questions

The returned result may include facts derived from that domain agent's own RAG repository, but those facts are still returned through the same structured contract.

## Clean component structure

### Master orchestration entry points

`SessionAgentManager` and `StreamingSessionAgentManager` should become thin entry points for the master agent only.

They should own:

- mode-specific request/response boundaries
- delivery mechanics
- calls into a shared orchestration core

They should not duplicate the core orchestration logic.

### Shared orchestration core

The orchestration flow shared today between synchronous and streaming chat should live in one shared abstraction.

That shared core owns:

- request classification
- session context assembly
- shared-tool routing
- domain-agent selection
- domain-agent invocation
- result composition
- failure handling
- audit shaping

### Delivery-mode adapters

Synchronous and streaming behavior should be modeled as small delivery-mode adapters around the same orchestration core.

Recommended design rule:

- if a change affects interpretation, routing, tool ownership, result structure, or failure handling, implement it once in the shared core
- if a change affects token delivery, stream framing, or return type, keep it mode-specific

This keeps sync and streaming as two delivery modes over one orchestration pipeline instead of two separately evolving implementations.

## Registry model

The architecture should no longer center on a single global tool registry for one large assistant.

Instead, the clean target model is:

- **master-visible agent registry**: which domain agents and shared tools the master may use
- **agent-local tool registry**: which tools belong to a given domain agent

Selection therefore happens in two stages:

1. master selects domain agents
2. each selected domain agent uses only its own tools

This keeps tool ownership explicit and prevents every tool from being visible to one global assistant.

## Error handling

- If one domain agent fails, the master should preserve successful results from other domain agents
- The master should mark the failed domain explicitly
- The master should decide whether to return a partial answer or ask a targeted follow-up based on how central that failed domain is to the request
- Domain-agent failures should not silently collapse into empty success-shaped outputs

## Testing focus

The design implies tests at these levels:

- master routing tests for single-domain, multi-domain, and shared-tool-only requests
- domain-agent tests to verify each agent only uses its own tool set
- shared orchestration core tests to ensure sync and streaming share routing and failure behavior
- delivery-mode tests that verify only output mechanics differ between sync and streaming
- contract tests for structured domain-agent results

## Design decisions captured

- Use a **router-style master agent** rather than a domain-first or heavy planner-first model
- Allow the master to coordinate multiple domain agents within a single request
- Keep long-lived memory in the master only
- Expose each current API operation as a tool inside its owning domain agent
- Use one domain agent per current MCP business domain/facade boundary
- Keep general knowledge tools at the master layer
- Favor the clean target architecture over migration safety
- Remove duplicated orchestration logic through a shared polymorphic orchestration core

## Open implementation implications

- The current `ToolRegistry` shape will need to evolve from role-to-tool-object lookup toward master-visible agents plus agent-local tools
- Current facade classes are better treated as domain boundaries than as final runtime abstractions
- Shared-tool policy should stay centralized in the master to avoid domain prompt sprawl
