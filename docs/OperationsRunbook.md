# Durion Positivity Backend Operations Runbook

## Overview

This runbook documents operational procedures for the **durion-positivity-backend** project (Spring Boot 3.x, Java 21). It focuses on building, deploying, monitoring, and troubleshooting the POS microservices that underpin the Durion platform.

This runbook is **service-local**. For cross-repository coordination (story orchestration, workspace-level agents, and SRE procedures that span frontend and backend), use the workspace-level runbook in the **durion/workspace-agents** project.

**Related runbooks and specs:**
- Workspace-level: `durion/workspace-agents/docs/OperationsRunbook.md`
- Workspace agent structure: `durion/.kiro/specs/workspace-agent-structure/tasks.md`
- Backend agent structure plan: `.kiro/specs/agent-structure/tasks.md`

**Operational targets (backend services):**
- Availability: 99.9% during business hours
- Typical response time: < 500 ms for core APIs under normal load
- RTO: 4 hours, RPO: 1 hour (aligned with workspace-level targets)

---

## How to Use This Runbook

Use this runbook when you are operating, deploying, or debugging **durion-positivity-backend** services:

- For **build & test** questions, see section 1.
- For **deployment** steps, see section 2.
- For **monitoring & alerting** guidance, see section 3.
- For **common incidents** (build failures, startup issues, health failures), see section 4.
- For **agent-structure / Kiro automation** for this repo, see section 5.

When an issue spans frontend (Moqui/Vue) and backend services, start here to verify the backend is healthy, then switch to the workspace-level runbook and the frontend runbook.

---

## 1. Build and Test

### 1.1 Local build

```bash
# From the repo root
cd durion-positivity-backend

# Clean build with tests
./mvnw -e -U -DskipTests=false -DfailIfNoTests=false clean test

# Full package build
./mvnw -e -U -DskipTests=false -DfailIfNoTests=false clean package
```

### 1.2 Module-focused build

Most business functionality lives in the `pos-*` modules and the `pos-agent-framework` module.

```bash
# Build only the agent framework
cd durion-positivity-backend
./mvnw -pl pos-agent-framework -am clean test

# Build and test a specific POS module (example: pos-order)
./mvnw -pl pos-order -am clean test
```

---

## 2. Deployment

Deployment details (Kubernetes, ECS, or other targets) may be environment-specific. This section captures the generic steps that apply across environments.

### 2.1 Build artifacts for deployment

```bash
cd durion-positivity-backend

# Build all services and create JARs/Docker images as configured
./mvnw -e -U -DskipTests=false -DfailIfNoTests=false clean package
```

Use your platform-specific pipelines or manifests (e.g., GitHub Actions, ArgoCD, Helm charts, or ECS task definitions) to deploy the resulting images/JARs.

### 2.2 Health and readiness checks

After deployment, verify that core services are healthy.

```bash
# Spring Boot actuator health for a given service (adjust host/port/path)
curl -f http://<host>:<port>/actuator/health || echo "health check failed"

# Example (if using a gateway)
# curl -f https://api.example.com/pos-order/actuator/health
```

---

## 3. Monitoring and Alerting

Monitoring is usually configured centrally (e.g., Prometheus/Grafana, OpenTelemetry). Backend services should expose metrics and health endpoints.

### 3.1 Key signals

- HTTP error rate (4xx/5xx)
- Request latency (p95, p99)
- JVM metrics (heap, GC, threads)
- Database connection pool usage

### 3.2 Example Prometheus-style alerts (illustrative)

```yaml
# High error rate
alert: PosBackendHighErrorRate
expr: rate(http_server_requests_seconds_count{status=~"5.."}[5m]) > 0.05
for: 5m
labels:
  severity: critical
annotations:
  summary: "High 5xx rate in durion-positivity-backend"

# High latency
alert: PosBackendHighLatency
expr: histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le)) > 1
for: 5m
labels:
  severity: warning
annotations:
  summary: "High p95 latency in durion-positivity-backend"
```

Use your existing monitoring stack to wire up these or similar alerts.

---

## 4. Troubleshooting

### 4.1 Build failures

```bash
cd durion-positivity-backend
./mvnw -e -U clean test

# If only one module is failing, narrow scope
./mvnw -pl pos-order -am clean test
```

Check the Maven output for failing tests or compilation errors. Fix locally, rerun tests, then push changes and let CI validate.

### 4.2 Service fails to start

Common causes:
- Missing environment variables (DB URLs, credentials, external service endpoints)
- Port conflicts
- Database connectivity issues

Basic checks:

```bash
# View logs (containerized)
kubectl logs deployment/pos-order -c pos-order --tail=200

# Or locally
java -jar pos-order/target/pos-order-*.jar
```

### 4.3 Health endpoint failing

```bash
curl -v http://<host>:<port>/actuator/health

# If DOWN, inspect logs and configuration for that service.
```

For incidents that involve cross-service behaviour (e.g., Moqui can’t reach a backend API), confirm the backend service is:
- Deployed and healthy
- Listening on the correct URL/path
- Accepting properly authenticated requests (JWT, API gateway rules)

Then follow the workspace-level runbook for cross-repo diagnosis.

---

## 5. Agent Structure and Kiro Automation

The backend agent structure plan for this repo lives in:

- `.kiro/specs/agent-structure/tasks.md`

To advance backend agent-structure work in a controlled way, use the Kiro helper script. Each run completes **exactly one** unchecked task in the spec and updates the associated HANDOFF file.

```bash
cd durion-positivity-backend

# Run a single Kiro step for the backend agent structure
MAX_STEPS=1 ./kiro-run-agent-structure.zsh
```

Use this when you want an agent (or automated process) to carry out one more step of the backend agent-structure implementation or maintenance without overstepping.

---

## 6. When to Escalate to Workspace-Level Runbook

Use the workspace-level runbook in `durion/workspace-agents/docs/OperationsRunbook.md` when:

- Issues involve both Moqui/frontend and backend services
- Story orchestration documents (story-sequence, frontend-/backend-coordination) appear inconsistent with actual behaviour
- You need coordinated SRE/DR actions that impact multiple repositories

Keep this backend runbook focused on what you can do **within this repo** and use the workspace-level runbook for cross-repo concerns.
