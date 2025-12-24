# Backend Agent Orchestrated Task Plan (Positivity Agent Structure)

## Purpose

Define concrete tasks for backend agents (durion-positivity-backend) using the Agent Structure System to:
- Follow workspace story sequencing and coordination documents
- Prioritize backend stories that unblock the most frontend work
- Avoid creating uncoordinated stub endpoints
- Operate in a silo while still aligning with frontend needs

This plan operationalizes REQ-016 and the Workspace Story Orchestration Integration section in the backend design.

## Inputs

- Workspace orchestration artifacts in durion:
  - durion/.github/orchestration/story-sequence.md
  - durion/.github/orchestration/backend-coordination.md
- Backend requirements and design:
  - positivity/.kiro/specs/agent-structure/requirements.md
  - positivity/.kiro/specs/agent-structure/design.md
- durion-positivity-backend [STORY] issues.

## Outputs

- Ordered list of backend [STORY] issues selected for implementation.
- Implemented Spring Boot services, endpoints, and persistence aligned with documented contracts.
- Updated issues and orchestration feedback when new dependencies or clarifications are discovered.

## Main Workflow

### 1. Refresh Orchestration Context

1. Read story-sequence.md to understand global story ordering and classification:
   - Especially Backend-First and Parallel stories relevant to backend.
2. Read backend-coordination.md to see backend-specific priorities:
   - Backend stories ordered by how many frontend stories they unblock.
   - Mapping from backend stories to dependent frontend stories and required contracts.
3. Verify timestamps and, if orchestration appears outdated, request a workspace re-run.

### 2. Build Backend-Oriented Backlog

1. From backend-coordination.md, build a candidate list:
   - Backend-First stories with high unblock value.
   - Parallel stories with well-defined contracts.
   - Lower-priority backend stories without current frontend dependencies.
2. Cross-reference each backend story with its [STORY] issue in durion-positivity-backend:
   - Confirm acceptance criteria, Notes for Agents, and referenced frontend behavior.

### 3. Apply Prioritization Rules

1. Prefer backend stories that:
   - Unblock multiple frontend stories.
   - Are classified Backend-First and currently block Ready frontend work.
2. Defer backend stories with no frontend dependencies when capacity is limited, unless they are critical for architecture or operational reasons.
3. For Parallel stories:
   - Ensure contracts are sufficiently detailed before committing (endpoints, request/response models, error semantics).

### 4. Implement Backend-First Stories

1. For each selected Backend-First story:
   - Design and implement the required Spring Boot APIs, domain models, and persistence as per backend design.
   - Ensure behavior and contracts match what backend-coordination.md and the corresponding [STORY] issue describe.
2. Update tests and documentation so frontend agents can rely on the implemented contracts without further clarification.
3. Once implementation is complete:
   - Update the [STORY] issue status.
   - Note that dependent frontend stories in backend-coordination.md are now unblocked (via issue comments for workspace orchestration).

### 5. Implement Parallel Stories Safely

1. For Parallel stories:
   - Implement APIs strictly according to the contracts and examples in orchestration docs and Notes for Agents.
   - Avoid introducing behavior changes that would break assumptions for frontend implementations already in progress.
2. Where additional details are needed:
   - Capture questions and proposed clarifications as comments on the [STORY] issue.
   - Wait for orchestration updates rather than ad-hoc aligning directly with frontend agents.

### 6. Stub and Placeholder Rules

1. Do NOT create stub endpoints or placeholder implementations unless:
   - backend-coordination.md or the [STORY] issue explicitly authorizes a stub.
   - Stub behavior, response shape, and replacement criteria are clearly specified.
2. When a stub is allowed:
   - Implement it behind a clear boundary (e.g., a specific service method or adapter class).
   - Mark the stub in code (following team conventions) and in the [STORY] issue so the workspace orchestration can track it.

### 7. Coordination via Issues (Silo-Friendly)

1. All cross-cutting questions or discoveries MUST be communicated via [STORY] issues, not direct communication with frontend agents.
2. When backend work reveals new dependencies or missing contracts:
   - Document these as Notes for Agents and explicit checklists inside the issue.
   - Tag or reference that orchestration documents should be updated accordingly.
3. Treat orchestration docs and issue metadata as the bridge between backend and frontend silos.

### 8. Feedback to Orchestration

1. After implementing or reprioritizing backend stories:
   - Comment on affected [STORY] issues with what changed and which frontend stories are now unblocked.
2. Suggest updates to backend-coordination.md where prioritization or contract details should change.
3. Expect the workspace agents to re-run orchestration and refresh story-sequence.md and backend-coordination.md accordingly.

## Silo Assumptions

- Backend agents rely only on orchestration docs, backend requirements/design, and GitHub issues.
- They do not assume real-time coordination with frontend agents.
- All contract and sequencing knowledge must be traceable in orchestration files and issue metadata.
