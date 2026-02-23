# pos-mcp-server

MCP server for Durion Positivity. Discovers backend services via Eureka, registers their REST APIs as MCP tools, and stores system prompts.

## Runtime configuration

Preprod/Prod profiles use PostgreSQL. Set these environment variables in the deployment manifest or process env:

- `POS_MCP_DB_HOST` – Postgres host (required)
- `POS_MCP_DB_PORT` – Postgres port (default `5432`)
- `POS_MCP_DB_NAME` – Database name (default `pos_mcp`)
- `POS_MCP_DB_USER` – Database user (default `pos_mcp`)
- `POS_MCP_DB_PASSWORD` – Database password (required)

Profiles:
- `spring.profiles.active=preprod` – PostgreSQL, Eureka enabled
- `spring.profiles.active=prod` – PostgreSQL, Eureka enabled
- `spring.profiles.active=test` – In-memory H2 for tests
- default (no profile) – In-memory H2 for local/dev

## Quick local run
```bash
./mvnw -pl pos-mcp-server -am spring-boot:run
# or
SPRING_PROFILES_ACTIVE=test ./mvnw -pl pos-mcp-server -am test
```
