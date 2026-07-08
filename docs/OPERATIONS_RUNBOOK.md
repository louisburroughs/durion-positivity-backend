# Operations Runbook

This document covers operational procedures, RBAC framework usage, and permission management for the durion-positivity-backend microservices platform.

## Table of Contents

1. [Build and Test](#build-and-test)
2. [Deployment](#deployment)
3. [Monitoring and Alerting](#monitoring-and-alerting)
4. [Troubleshooting](#troubleshooting)
5. [RBAC Framework](#rbac-framework)
6. [Permission Registration](#permission-registration)

---

## Build and Test

### Local Build

```bash
cd durion-positivity-backend

# Clean build with tests
./mvnw -e -U -DskipTests=false -DfailIfNoTests=false clean test

# Full package build
./mvnw -e -U -DskipTests=false -DfailIfNoTests=false clean package
```

### Module-Focused Build

```bash
# Build and test a specific module
./mvnw -pl pos-order -am clean test
```

---

## Deployment

### Build Artifacts

```bash
./mvnw -e -U -DskipTests=false -DfailIfNoTests=false clean package
```

### Health and Readiness Checks

```bash
# Check service health
curl -f http://<host>:<port>/actuator/health || echo "health check failed"

# Check all services
for port in 8080 8761; do
  echo "Port $port:"
  curl -s http://localhost:$port/actuator/health | jq .
done
```

### Operational Targets

| Metric        | Target                      |
| ------------- | --------------------------- |
| Availability  | 99.9% during business hours |
| Response Time | < 500ms for core APIs       |
| RTO           | 4 hours                     |
| RPO           | 1 hour                      |

---

## Monitoring and Alerting

### Key Signals

- HTTP error rate (4xx/5xx)
- Request latency (p95, p99)
- JVM metrics (heap, GC, threads)
- Database connection pool usage

### Example Prometheus Alerts

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

### Dashboard Access

| Dashboard  | URL                      | Credentials |
| ---------- | ------------------------ | ----------- |
| Grafana    | <http://localhost:3000>  | admin/admin |
| Jaeger     | <http://localhost:16686> | -           |
| Prometheus | <http://localhost:9090>  | -           |
| Eureka     | <http://localhost:8761>  | -           |

---

## Troubleshooting

### Build Failures

```bash
./mvnw -e -U clean test

# If only one module is failing
./mvnw -pl pos-order -am clean test
```

### Service Fails to Start

**Common causes:**

- Missing environment variables
- Port conflicts
- Database connectivity issues

**Basic checks:**

```bash
# View logs (containerized)
kubectl logs deployment/pos-order -c pos-order --tail=200

# Or locally
java -jar pos-order/target/pos-order-*.jar
```

### Health Endpoint Failing

```bash
curl -v http://<host>:<port>/actuator/health
```

**If DOWN, check:**

- Database connectivity
- External service dependencies
- Memory/CPU constraints

### Port Conflicts

```bash
# Find what's using a port
lsof -i :8080
netstat -tlnp | grep 8080

# Docker volume issues
docker-compose down -v
docker volume prune
```

---

## RBAC Framework

### Overview

The POS Security Service provides Role-Based Access Control (RBAC) for all microservices.

### Creating Roles and Permissions

**1. Register a Permission:**

```bash
POST /api/permissions/register
Content-Type: application/json
Authorization: Bearer {JWT_TOKEN}

{
  "name": "financial:refund:approve",
  "description": "Approve customer refund requests",
  "registeredByService": "pos-accounting"
}
```

**2. Create a Role:**

```bash
POST /api/roles
Content-Type: application/json
Authorization: Bearer {JWT_TOKEN}

{
  "name": "Cashier",
  "description": "Front desk cashier role with basic POS operations"
}
```

**3. Assign Permissions to Role:**

```bash
PUT /api/roles/permissions
Content-Type: application/json
Authorization: Bearer {JWT_TOKEN}

{
  "roleId": 1,
  "permissionNames": [
    "pos:order:create",
    "pos:order:view",
    "pos:payment:accept"
  ]
}
```

### Assigning Roles to Users

**Global Scope:**

```bash
POST /api/roles/assignments
Content-Type: application/json
Authorization: Bearer {JWT_TOKEN}

{
  "userId": 123,
  "roleId": 1,
  "scopeType": "GLOBAL",
  "effectiveStartDate": "2026-01-13"
}
```

**Location-Scoped:**

```bash
POST /api/roles/assignments
Content-Type: application/json
Authorization: Bearer {JWT_TOKEN}

{
  "userId": 123,
  "roleId": 2,
  "scopeType": "LOCATION",
  "scopeLocationIds": ["STORE-001", "STORE-002"],
  "effectiveStartDate": "2026-01-13",
  "effectiveEndDate": "2026-12-31"
}
```

### Checking Permissions

```bash
# Check if user has permission
GET /api/roles/check-permission?userId=123&permission=pos:order:create&locationId=STORE-001

# Get all user permissions
GET /api/roles/permissions/user/123

# Get user's role assignments
GET /api/roles/assignments/user/123
```

### Permission Naming Convention

Format: `domain:resource:action` (snake_case, lowercase)

| Domain    | Resource   | Action  | Full Permission                |
| --------- | ---------- | ------- | ------------------------------ |
| crm       | party      | view    | `crm:party:view`               |
| inventory | adjustment | approve | `inventory:adjustment:approve` |
| order     | shipment   | cancel  | `order:shipment:cancel`        |
| security  | role       | assign  | `security:role:assign`         |

---

## Permission Registration

### Code-First Pattern

Permissions are defined in code and registered via API during service startup (DECISION-INVENTORY-006).

**1. Define Permissions:**

```java
public class CrmPermissionRegistry {
    public static PermissionRegistrationRequest buildCrmPermissionRegistration() {
        return PermissionRegistrationRequest.builder()
            .domain("crm")
            .serviceName("pos-customer")
            .permissions(Arrays.asList(
                Permission.builder()
                    .name("crm:party:view")
                    .description("View customer party records")
                    .build(),
                Permission.builder()
                    .name("crm:party:create")
                    .description("Create new customer party records")
                    .build()
            ))
            .build();
    }
}
```

**2. Register on Startup:**

```java
@Configuration
public class CrmPermissionInitializer {

    @Value("${gateway.url:http://localhost:8080}")
    private String gatewayUrl;

    @Bean
    public ApplicationRunner registerCrmPermissions(RestClient restClient) {
        return args -> {
            try {
                var request = CrmPermissionRegistry.buildCrmPermissionRegistration();

                restClient.post()
                    .uri(gatewayUrl + "/security-service/v1/permissions/register")
                    .header("X-API-Version", "1")
                    .body(request)
                    .retrieve()
                    .toEntity(Void.class);

                log.info("✓ CRM permissions registered successfully");
            } catch (Exception e) {
                log.warn("⚠ Failed to register permissions: {}", e.getMessage());
            }
        };
    }
}
```

**3. Enforce Authorization:**

```java
@RestController
@RequestMapping("/v1/crm")
public class PartyController {

    @GetMapping("/parties")
    @PreAuthorize("hasAuthority('crm:party:view')")
    public List<Party> listParties() {
        // ...
    }

    @PostMapping("/parties")
    @PreAuthorize("hasAuthority('crm:party:create')")
    public Party createParty(@RequestBody PartyRequest request) {
        // ...
    }
}
```

### Anti-Patterns (Do NOT Do)

❌ Store permissions in application.yml
❌ Manually insert permissions into database
❌ Create permissions via admin UI

---

## Domain Events (Kafka, ADR-0044)

Module-to-module communication flows over Kafka domain topics (`{domain}.events.v1` facts,
`{domain}.commands.v1` commands). Contracts live in `pos-domain-events`; the full policy is
`docs/adr-0044-event-only-domain-walls.md`.

### Local broker

`docker-compose up -d kafka` starts a single-node KRaft broker (`apache/kafka`). Services reach it
at `kafka:29092` inside the compose network (host tools at `localhost:9092`). Kafka features remain
opt-in per module (e.g. `WORKORDER_KAFKA_ENABLED=true`, `pos.customer.kafka.enabled=true`) until
the Phase 0.4 tier-1 flip.

### Transactional outbox (producers)

Producers write events to their `event_outbox` table in the business transaction; a scheduled
publisher drains to Kafka (at-least-once). Operational signals:

- Metrics: `workorder.outbox.published`, `workorder.outbox.publish.failures` (counter deltas);
  a growing gap means the drain is failing.
- `SELECT count(*) FROM event_outbox WHERE published_at IS NULL` — sustained growth means the
  broker is unreachable or the publisher is stopped; check `attempts`/`last_error` on stuck rows.
- The publisher halts its batch at the first failure to preserve order — one poisoned/oversized
  record blocks the drain; inspect the oldest unpublished row first.

### Consumers: retry and DLQ

Consumers retry failed records with exponential backoff, then dead-letter to `{topic}.dlq`
(e.g. `workorder.events.v1.dlq`). Redelivery is safe: consumers deduplicate by `eventId`
(unique-keyed processing log). To inspect a DLQ:

```bash
docker exec kafka-positivity /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic workorder.events.v1.dlq --from-beginning --max-messages 10
```

To reprocess a DLQ'd record after fixing the cause, re-emit it from the owner's outbox (below) —
do not hand-copy messages between topics.

### Replica seeding and drift repair (replay)

Owners expose an administrative re-emit endpoint (permission `{domain}:events:replay`), e.g.:

```bash
# Re-emit all workorder events created since July 1 (idempotent for consumers)
curl -X POST "https://<gateway>/workorder/v1/outbox/replay?since=2026-07-01T00:00:00Z" \
  -H "Authorization: Bearer $TOKEN" -H "X-API-Version: 1"
```

Seeding a brand-new replica: create the consumer's `ext_*` tables (Flyway), start the consumer,
then call replay with `since` at the epoch (omit the parameter). Consumers skip anything already
processed.

## Related Documentation

- **Platform-level runbook**: `durion/docs/OPERATIONS_RUNBOOK.md`
- **Architecture guide**: [ARCHITECTURE_GUIDE.md](ARCHITECTURE_GUIDE.md)
- **Development guide**: [DEVELOPMENT_GUIDE.md](DEVELOPMENT_GUIDE.md)
