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

The official Onyx local Docker docs use an install-script flow rather than asking you to hand-write an Onyx image block in this backend repo.

The current documented flow includes:

- `onyx/deployment/docker_compose`
- `./install.sh`
- generated deployment files under `onyx_data/deployment`
- generated runtime env at `onyx_data/deployment/.env`
- generated main compose file at `onyx_data/deployment/docker-compose.yml`
- `docker compose up -d` from that generated deployment directory

Recommended local startup commands:

```bash
./mvnw -pl pos-mcp-server -am package -DskipTests
docker compose -f docker-compose.yml -f docker-compose.onyx.yml up -d
```

Then follow the official Onyx Docker steps from the Onyx repo/docs to install and run Onyx itself.

Current caveats:

- `pos-mcp-server` is now built locally by the root override, so the module jar must exist in `pos-mcp-server/target/` before `docker compose up`.
- the official Onyx deployment must still be configured to reach the MCP server and Ollama endpoints exposed by the backend companion stack

## Recommended operating model

Use two separate runtimes:

- this backend repo runs the companion services:
  - `pos-mcp-server`
  - `ollama`
- the official Onyx deployment runs from its generated `onyx_data/deployment` directory

That means:

- keep backend companion wiring in this repo
- keep Onyx platform settings in `onyx_data/deployment/.env`
- finish model and tool integration through the local Onyx UI after startup

This is the practical sequence:

1. build and start the backend companion stack
2. install and start Onyx from `onyx_data/deployment`
3. open the local Onyx instance in the browser
4. log in with the local admin/basic auth flow
5. configure Ollama and MCP connectivity in Onyx
6. validate one low-risk tool call end to end

## Bring-up sequence

### Step 1: Build and start backend companion services

From the backend repo:

```bash
./mvnw -pl pos-mcp-server -am package -DskipTests
docker compose -f docker-compose.yml -f docker-compose.onyx.yml up -d
```

Confirm the companion endpoints:

- `pos-mcp-server` health at `http://localhost:8094/actuator/health`
- `ollama` API at `http://localhost:11434/api/tags`

If Ollama is empty, pull the model you want before moving on.

Example:

```bash
docker exec -it ollama ollama pull llama3.1:8b
```

### Step 2: Install and start the official Onyx deployment

Follow the official Onyx Docker instructions from the Onyx repo or docs.

The important file locations after install are:

- `onyx_data/deployment/.env`
- `onyx_data/deployment/docker-compose.yml`

Start Onyx from that generated deployment directory:

```bash
cd onyx_data/deployment
docker compose up -d
```

### Step 3: Log into the local Onyx instance

After startup:

- open the local Onyx URL in the browser
- log in using the local auth flow configured by the generated Onyx deployment
- complete any first-run admin or user setup required by the local instance

This is where I would expect most application-level configuration to happen.

### Step 4: Configure Ollama inside Onyx

In the local Onyx admin or model settings:

- choose the Ollama provider if supported by the installed Onyx version
- point it at the reachable Ollama endpoint
- use a model name that exactly matches what Ollama has pulled

Typical local endpoint choices:

- `http://host.docker.internal:11434` if Onyx runs in a separate Docker stack and needs to reach the host-mapped port
- another Docker-reachable address if both stacks share a network by design

Validation target:

- Onyx can complete a simple test prompt using the configured Ollama model

### Step 5: Configure MCP access inside Onyx

In the local Onyx MCP, tools, or connector configuration area:

- add the Durion MCP server
- point it at the reachable `pos-mcp-server` endpoint
- use the transport expected by the installed Onyx version

Typical local endpoint:

- `http://host.docker.internal:8094`

If the selected Onyx version expects a specific MCP path such as SSE, use the exact reachable MCP endpoint exposed by `pos-mcp-server`.

Validation target:

- Onyx can discover the Durion tool set from `pos-mcp-server`
- tool names and schemas render correctly

### Step 6: Run one small end-to-end test

Use one safe, read-only tool first.

Recommended first test:

1. ask a question that should use a simple lookup tool
2. confirm Onyx selects the tool
3. confirm `pos-mcp-server` receives the request
4. confirm a valid response returns to the Onyx UI

Do not start with broad write-capable tools.

## Phase 1: Confirm integration surface

- Verify the selected Onyx version supports remote MCP server connections.
- Verify whether Onyx supports MCP over SSE using `pos-mcp-server` endpoints.
- Verify how Onyx authenticates to MCP servers, if authentication is required.
- Verify how Onyx expects tool server metadata to be registered.
- Verify how the official Onyx Docker deployment configures Ollama and MCP server connectivity.

Exit criteria:

- You know exactly where to set Onyx MCP and Ollama settings in the official deployment.

## Phase 2: Stand up local dependencies

- Follow the official Onyx install flow from its docs and generated deployment directory.
- Start `ollama`.
- Pull the target chat model, such as `llama3.1:8b`.
- Pull the target embedding model if Onyx uses one, such as `nomic-embed-text`.
- Start PostgreSQL for `pos-mcp-server` if you are using the `preprod` profile locally.
- Start `pos-mcp-server`.
- Confirm `pos-mcp-server` health and MCP endpoints are reachable from the Docker network.

Validation:

- `curl http://localhost:8094/actuator/health`
- verify the MCP endpoint using the reachable host-mapped address exposed by your local stack

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
- Use whichever address is reachable from the official Onyx deployment, for example a host-mapped port if the stacks run separately.
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

1. Start the backend companion stack from this repo.
2. Start the official Onyx deployment from `onyx_data/deployment`.
3. Log into the local Onyx UI.
4. Configure Ollama in Onyx and validate a plain model response.
5. Configure `pos-mcp-server` in Onyx and validate one safe read-only tool.
6. Confirm the request is visible in logs and trace metadata.

## Suggested follow-up decisions

1. Decide whether `pos-mcp-server` should expose all discovered tools or only curated facade tools.
2. Decide whether the tool registry from [tool-registry-implementation.md](/home/louis-burroughs/IdeaProjects/durion-positivity-backend/pos-mcp-server/docs/tool-registry-implementation.md) is needed in phase 1 or can wait until tool count grows.
3. Decide whether the direct LLM integration code in `pos-mcp-server` should be kept as fallback or removed to reduce confusion.

## Recommendation

My recommendation is to make the first milestone boring on purpose:

- one official Onyx deployment version
- one Ollama chat model
- one embedding model only if Onyx actually requires it immediately
- three to five low-risk MCP tools

That will tell us quickly whether the Onyx + Ollama + MCP shape is solid before we spend energy on registry scoring or larger tool inventories.
