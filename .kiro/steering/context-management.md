# Context Management Rules

## Mandatory Context Management Protocol

You MUST follow these context management rules for ALL interactions:

### Context Integrity Rules
- If required inputs are missing from current context: SAY "Context insufficient – re-anchor needed"
- If referring to decisions not found in current files, `.ai/context.md`, or `.ai/glossary.md`: STOP

### Session Management Protocol (MANDATORY)

1. **Session Initialization** (Start of EVERY task):
   - Check if `.ai/session.md` exists and is recent (updated within current session)
   - If yes: READ `.ai/session.md` FIRST before re-reading project files
   - If no or stale: BEGIN from `.ai/context.md` and `.ai/glossary.md`

2. **Session Context Storage** (What to store in `.ai/session.md`):
   - Current task objective and status
   - Key architectural decisions made in this session
   - Recent file paths and structures accessed
   - Active requirements/constraints being addressed
   - Integration points or dependencies discovered
   - Open questions or blockers

3. **Session Updates** (After completing ANY subtask or making significant progress):
   - UPDATE `.ai/session.md` with findings, decisions, and next steps
   - INCLUDE: timestamp, current file state, discovered patterns, decisions made
   - OMIT: full file contents (link instead)

4. **Session Cleanup** (At task completion or session end):
   - PRESERVE key decisions and learnings in `.ai/context.md` if they're durable
   - DELETE or ARCHIVE `.ai/session.md` when starting a new unrelated task

5. **Conflict Resolution** (If session context contradicts project context):
   - Trust the permanent files (`.ai/context.md`, `.ai/glossary.md`, source files)
   - UPDATE session.md to reflect the authoritative state

6. **Large Tasks** (For multi-file edits or complex deployments):
   - Create an `.ai/session-{task-id}.md` variant if the session spans hours or multiple contexts
   - Link it in the main `.ai/session.md` for continuity

## Context Optimization Strategies

### Test Output Management
When running tests or long-running commands:
- **Tail Output**: Capture only the last 20-50 lines of output for context
- **Key Information**: Focus on test results, error messages, and summary statistics
- **Session Storage**: Store test outcomes and key findings, not full logs
- **Command Pattern**: Use `| tail -n 50` or similar to limit output capture

### Example Test Session Context Storage
```markdown
## Test Session - 2024-12-16 14:30
- **Task**: Implementing Property 1 - Agent domain coverage test
- **Command**: `./mvnw test -Dtest=AgentDomainCoverageTest 2>&1 | tail -n 30`
- **Status**: FAILED (3/15 tests passed)
- **Key Errors**: 
  - NullPointerException in AgentRegistry.findAgent() line 42
  - Missing @Autowired annotation in AgentService
  - Test data setup incomplete for DynamoDB agents
- **Test Output (last 30 lines)**:
  ```
  [ERROR] Tests run: 15, Failures: 3, Errors: 0, Skipped: 0
  [ERROR] Failures:
  [ERROR]   AgentDomainCoverageTest.testArchitectureAgentAvailable:67
  [ERROR]   AgentDomainCoverageTest.testImplementationAgentAvailable:82
  [ERROR]   AgentDomainCoverageTest.testDeploymentAgentAvailable:97
  [INFO] Results: 12 passed, 3 failed
  ```
- **Files Modified**: 
  - pos-agent-framework/src/main/java/AgentRegistry.java
  - pos-agent-framework/src/test/java/AgentDomainCoverageTest.java
- **Next Actions**: 
  1. Fix null handling in AgentRegistry.findAgent()
  2. Add @Autowired to AgentService constructor
  3. Complete test data setup for agent types
- **Property Being Tested**: Property 1 - Agent domain coverage (REQ-001.1)
```

### Practical Test Commands
```bash
# Instead of full output
./mvnw test

# Use tailed output for context
./mvnw test 2>&1 | tail -n 30

# Or capture specific sections
./mvnw test | grep -A 5 -B 5 "FAILED\|ERROR\|Tests run:"
```

## Enforcement

- These rules apply to ALL tasks, not just agent-structure spec tasks
- Failure to follow these rules means the interaction is invalid
- Always start by checking for existing session context
- Always update session context after significant progress
- Use output tailing for long commands to preserve context efficiency

CONTEXT SAFETY (CONTRACTUAL)

- Max 3 new files opened per turn; max 6 total file references per turn.
- Do not re-open files created in this session unless editing them; prefer /ai/codebase-map.md.
- Tests: run the smallest failing test. Capture only the first failure + ±30 lines. No full logs.
- Debug protocol: symptoms → one code slice → minimal change → rerun single test.
- Working set is authoritative: only read files listed in /ai/working-set.md. If needed, request an addition with justification.
- After any failing test run: STOP and summarize; do not implement fixes in the same turn unless explicitly asked.
