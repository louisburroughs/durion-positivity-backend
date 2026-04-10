# Onyx + Ollama Integration Checklist

## Purpose

This checklist turns the revised [llm-tool-orchestration-spec.md](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/docs/llm-tool-orchestration-spec.md) into an executable rollout plan.

Use it when standing up:

- an `Onyx` container
- an `Ollama` model runtime
- `pos-mcp-server` as the MCP tool host

Reference sample compose file:

- [docker-compose.onyx-ollama-mcp.yml](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/docs/docker-compose.onyx-ollama-mcp.yml)
- [docker-compose.onyx.yml](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/docker-compose.onyx.yml)

## Important note

The exact Onyx container image name, environment variables, and MCP connector configuration must be confirmed against the specific Onyx version you deploy.

This checklist and compose file are intentionally opinionated templates, not a guarantee of Onyx-specific key names.

Recommended local startup command:

```bash
./mvnw -pl pos-mcp-server -am package -DskipTests
docker compose -f docker-compose.yml -f docker-compose.onyx.yml up -d
```

Current caveats:

- `pos-mcp-server` is now built locally by the root override, so the module jar must exist in `pos-mcp-server/target/` before `docker compose up`.
- The exact Onyx image name and environment variables still need to be confirmed for your chosen Onyx version.

## Phase 1: Confirm integration surface

- Verify the selected Onyx version supports remote MCP server connections.
- Verify whether Onyx supports MCP over SSE using `pos-mcp-server` endpoints.
- Verify how Onyx authenticates to MCP servers, if authentication is required.
- Verify how Onyx expects tool server metadata to be registered.
- Verify whether Ollama configuration is direct environment-based or stored through Onyx admin setup.

Exit criteria:

- You know the exact Onyx image tag to use.
- You know the exact Onyx config keys for Ollama and MCP server registration.

## Phase 2: Stand up local dependencies

- Start `ollama`.
- Pull the target chat model, such as `llama3.1:8b`.
- Pull the target embedding model if Onyx uses one, such as `nomic-embed-text`.
- Start PostgreSQL for `pos-mcp-server` if you are using the `preprod` profile locally.
- Start `pos-mcp-server`.
- Confirm `pos-mcp-server` health and MCP endpoints are reachable from the Docker network.

Validation:

- `curl http://localhost:8086/actuator/health`
- verify the SSE endpoint resolves at `http://localhost:8086/mcp/sse`

## Phase 3: Verify Ollama connectivity

- Confirm Ollama responds on `http://localhost:11434/api/tags`.
- Confirm the desired models are present.
- Validate one manual chat request against Ollama.
- Validate one manual embedding request if embeddings are enabled.

Validation:

- `curl http://localhost:11434/api/tags`
- confirm the configured model names exactly match what Onyx will request

## Phase 4: Register pos-mcp-server with Onyx

- Configure Onyx to connect to `pos-mcp-server`.
- Use the internal container hostname when running on the same Docker network.
- Confirm Onyx can list tools from the MCP server.
- Confirm tool schemas render correctly inside Onyx.

Validation:

- Onyx shows the Durion MCP tool set
- tools are callable from the Onyx interface or admin test flow

## Phase 5: Start with a curated tool set

- Expose only a small set of safe facade tools first.
- Prefer read-only or low-risk tools for the first pass.
- Avoid exposing raw, high-volume OpenAPI-discovered tools until naming and schemas are reviewed.
- If necessary, temporarily disable broad discovery and register only hand-picked tools.

Recommended first tools:

- health or connectivity tool
- inventory lookup
- order lookup
- customer lookup

## Phase 6: Security validation

- Confirm backend authorization still applies when a tool is called through `pos-mcp-server`.
- Confirm unauthorized tools fail closed.
- Confirm internal-only services are not accidentally exposed.
- Confirm secrets are provided through environment variables or secret storage, not committed config.

Validation:

- run at least one authorized tool call
- run at least one unauthorized tool call and confirm denial behavior

## Phase 7: Observability validation

- Confirm correlation IDs are present across tool requests.
- Confirm `pos-mcp-server` logs tool invocation success and failure.
- Confirm timeouts are distinguishable between Onyx, Ollama, and backend tool execution.
- Add dashboards or logs for:
  - Onyx unreachable
  - Ollama unreachable
  - MCP endpoint unreachable
  - tool invocation failure

## Phase 8: Operational hardening

- pin specific image tags instead of `latest`
- persist Ollama model data to a named volume
- add restart policies
- define resource limits for Onyx and Ollama
- add health checks for each container
- move passwords and secrets to `.env` or secret management

## Suggested first acceptance test

1. Start the stack with the sample compose file adapted to your Onyx version.
2. Ask Onyx a question that should trigger a safe read-only MCP tool.
3. Confirm the tool is invoked through `pos-mcp-server`.
4. Confirm the response returned to the user includes tool-derived data.
5. Confirm the request is visible in logs and trace metadata.

## Suggested follow-up decisions

1. Decide whether `pos-mcp-server` should expose all discovered tools or only curated facade tools.
2. Decide whether the tool registry from [tool-registry-implementation.md](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/docs/tool-registry-implementation.md) is needed in phase 1 or can wait until tool count grows.
3. Decide whether the direct LLM integration code in `pos-mcp-server` should be kept as fallback or removed to reduce confusion.

## Recommendation

My recommendation is to make the first milestone boring on purpose:

- one Onyx image version
- one Ollama chat model
- one embedding model only if Onyx actually requires it immediately
- three to five low-risk MCP tools

That will tell us quickly whether the Onyx + Ollama + MCP shape is solid before we spend energy on registry scoring or larger tool inventories.
