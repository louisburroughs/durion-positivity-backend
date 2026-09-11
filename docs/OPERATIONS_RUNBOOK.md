# Operations Runbook

This document covers operational procedures, RBAC framework usage, and permission management for the durion-positivity-backend microservices platform.

## Table of Contents

1. [Build and Test](#build-and-test)
2. [Deployment](#deployment)
3. [Monitoring and Alerting](#monitoring-and-alerting)
4. [Troubleshooting](#troubleshooting)
5. [RBAC Framework](#rbac-framework)
6. [Tenant Provisioning](#tenant-provisioning)
7. [Permission Registration](#permission-registration)

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

### Alpha deployment paths (#1457)

Two workflows deliver changes to the alpha EC2 box; which one runs depends on what changed:

- **Code changes** — `build-push-ecr.yml` (auto after a green `Backend CI/CD` main run, or
  manual dispatch with `deploy_alpha`). Builds/promotes images, uploads the compose files to S3,
  pulls them onto the box over SSM, and runs `deploy-backend.sh <sha>` (full deploy:
  retag + pull + `--force-recreate`).
- **Config-only changes** to `deployment/alpha/docker-compose.prod.yml`,
  `deployment/alpha/deploy-backend.sh`, `deployment/alpha/cloudwatch-agent-config.json`,
  `deployment/alpha/install-cloudwatch-agent.sh`, `postgres/init-databases.sql`, or
  `observability/**` —
  `sync-alpha-config.yml` (auto on merge to `main` touching those paths, or manual dispatch).
  Uploads the committed files, pulls them onto the box, and runs
  `deploy-backend.sh --config-only`, which **recreates** exactly the containers whose merged
  compose config changed, force-recreates the observability containers (their bind-mounted
  configs are invisible to compose's config hash), re-provisions Kafka topics, and reconciles
  databases. Recreation matters: a plain `docker restart` keeps the old environment, so new
  `environment:` values would never apply.
- The root `docker-compose.yml` rides **both** paths: the sync workflow delivers and applies
  it, but it also remains a full-rebuild trigger in `build-push-ecr.yml` because it carries
  `build:` contexts — expect both workflows to run on a root-compose change (they converge on
  the committed state).
- **Schema reset** (ADR-0062 alpha): while the platform is in alpha, Flyway baselines are edited
  in place rather than migrated (`docs/TENANCY_SCHEMA.md`), so a box whose databases predate a
  baseline change fails validation on the first recreated service (`checksum mismatch for
  migration version 1`, run 34529050551). Dispatch `build-push-ecr.yml` on `main` with
  `deploy_alpha=true` **and** `reset_alpha_databases=true`: the deploy stops the backend tier,
  drops every database named in `postgres/init-databases.sql` `WITH (FORCE)`, recreates it, re-runs
  the `pos_app` grants and starts the tiers, so every service rebuilds its schema from
  `V1__baseline_<module>.sql` plus seeds. All alpha data is lost; the automatic promotion path
  and the config-only sync never reset (the script refuses `RESET_DATABASES=true` on
  `--config-only`). Procedure and verification: `docs/runbooks/flyway-baseline-reset.md`,
  "Alpha Cutover". Covered by `scripts/tests/deploy-backend-reset-databases-selftest.sh`.

Both paths pass the committed files' sha256 digests (`PROD_OVERRIDE_SHA256`,
`BASE_COMPOSE_SHA256`) into `deploy-backend.sh`, which refuses to compose against an on-box file
that does not match — a stale override is a loud failure, not a silent no-op. `--config-only`
also refuses to run on a box that has never had a full deploy (no `BACKEND_TAG` in `.env`).

`--config-only` resolves every backend image before it touches a container, because the box stays
pinned to the `BACKEND_TAG` of the last full deploy and a config sync never advances it. What it
does with an image it cannot resolve depends on whether the service is already here:

- **No container on the box** — the service was added by the commit being synced, and its image is
  published only by the `build-push-ecr` run that follows the merge, so it cannot exist at the
  pinned tag. The sync skips it with a warning and applies everything else; the service comes up
  with that run's alpha deploy, which moves the box to a tag that has it. Nothing to do. That run
  also creates the service's ECR repository when it is missing (`durion/<service>`, except
  `pos-service-discovery`, which publishes to `durion/eureka-server`; nothing else creates it, as
  this repo has no infrastructure-as-code for the AWS side), so a new service needs no manual AWS
  step before its first push.
- **A container exists** — the service was deployed at this tag, so its image has been retagged or
  deleted in ECR. The sync stops before changing anything. Re-run `build-push-ecr` on `main` with
  `deploy_alpha=true` to republish every service at a new tag and deploy it.

The skip is the reason a new-service merge no longer shows a red **Sync Alpha Config** run beside a
green build (`pos-reference-mock`, #1646). Behaviour is covered by
`scripts/tests/deploy-backend-config-only-selftest.sh`.

### Service secrets on alpha (on-box `.env`)

Compose interpolates the service-to-service secrets from the on-box env file
(`${ALPHA_ROOT}/.env`); `deploy-backend.sh` writes only `BACKEND_TAG`, `ECR_REGISTRY`,
`SECURITY_SEED_ADMIN_PASSWORD_HASH` and `SUPPLIER_AUDIT_ENC_KEY` there itself. Every other secret
is an entry an operator adds once, by hand, before the service that needs it is deployed; a
missing entry interpolates to empty and the receiving service fails closed (a 401 on the guarded
path), it does not fall back.

| Entry | Consumed by | Guards |
| --- | --- | --- |
| `POS_EVENTS_API_SECRET` | `pos-event-receiver` and every emitting module | `X-Events-Api-Secret` on event and event-type registration |
| `POS_SECURITY_API_SECRET` | `pos-security-service` and every registering module | `X-Permissions-Api-Secret` on `/v1/permissions/register` |
| `POS_TENANT_REGISTRY_API_SECRET` | `pos-tenant`, and any module with `pos.tenancy.registry.mode=REMOTE` (as `pos.tenancy.registry.secret`) | `X-Tenant-Registry-Secret` on `GET /internal/v1/tenants` (ADR-0062 plan WS4-2) |

The root `docker-compose.yml` passes `POS_TENANT_REGISTRY_API_SECRET` through from the environment
with no default (like the other service secrets): unset, `pos-tenant` refuses every registry call
with a 401, so the endpoint is closed until the secret exists. Set it in `.env` (`openssl rand -hex
32`) locally and on alpha before any module is switched to `REMOTE`, and give the same value to
those modules. Rotating it is two config syncs: change the
entry, recreate `pos-tenant`, then the consumers (their `RemoteTenantRegistry` keeps the last good
snapshot while the values disagree, logging one WARN per module until the next refresh succeeds).

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

### Host Metrics on Alpha (CloudWatch Agent)

Everything above is telemetry from inside the containers, scraped by the Prometheus stack that runs
on the alpha box. cAdvisor does expose some host-level filesystem and machine metrics there, but two
things make it the wrong place to rely on for this: **no Alertmanager is deployed** — the Prometheus
alert examples above notify nobody — and a stack that lives on the box cannot tell you the box is
in trouble. That is why host alerting goes through CloudWatch and SNS instead.

That gap caused #1862. The root disk filled with stale deploy images, deploys began failing on
`no space left on device`, and — because a full root filesystem also stops the SSM agent writing its
output — there was no remote shell left to diagnose it with. Nothing alarmed beforehand: EC2
publishes CPU and network from the hypervisor, but disk and memory live inside the instance and need
an agent there, and none was installed.

**What is collected**, from `deployment/alpha/cloudwatch-agent-config.json`, namespace `CWAgent`,
at 60s:

| Metric | Why |
| --- | --- |
| `disk_used_percent`, `disk_free` on `/` | Docker's data root lives here; this is the #1862 signal |
| `mem_used_percent` | Diagnosis. No alarm, to keep the noise down |

`aggregation_dimensions` publishes an `InstanceId`-only rollup *in addition to* the base series, so
disk bills as four custom metrics: two measurements across `(InstanceId, path, fstype)` and
`(InstanceId)`. Memory bills as one, not two — `append_dimensions` already makes its base dimension
set exactly `(InstanceId)`, so the rollup is the same series. Five in total.

**Alarms** (`us-east-1`, both notifying the `durion-alpha-alerts` SNS topic):

| Alarm | Fires | Missing data | Meaning |
| --- | --- | --- | --- |
| `alpha-root-disk-low` | `disk_free` < 40 GiB for 15 min | **breaching** | Lead time. Still above the deploy's 25 GiB reclaim floor, so nothing is broken yet |
| `alpha-root-disk-critical` | `disk_free` < 15 GiB for 10 min | missing | Below the reclaim floor: every deploy is pruning to make room, one cycle from the #1862 wedge |

Only the warning treats missing data as breaching, and that is deliberate: it is the alarm that
catches a dead agent, at a 15-minute window that tolerates a reboot. The critical alarm stays silent
on missing data so a reboot does not page twice for one event. The consequence is worth knowing —
**if the agent dies, the warning is what tells you, not the critical.**

Thresholds are in bytes, not percent, because the question is whether the next deploy's ~14 GB of
images fit, and that does not scale with volume size — the resize from 100 to 200 GiB halved every
percentage without changing the risk.

Three couplings to respect:

- Both alarms match on `InstanceId` alone, and that series exists **only** because of
  `aggregation_dimensions: [["InstanceId"]]`. `drop_device: true` leaves the base disk series at
  `(InstanceId, path, fstype)`, which the alarms would not match. Remove or narrow that key and
  `alpha-root-disk-critical` goes to INSUFFICIENT_DATA while `alpha-root-disk-low` alarms forever,
  with no change to the alarms themselves.

- The 40 GiB threshold is set against `DOCKER_MIN_FREE_GIB` in `deploy-backend.sh` (default 25) so
  the warning arrives *before* the deploy has to prune at all. Change one, revisit the other.
- The alarm watches `/`, while the deploy's reclaim measures `docker info --format
  '{{.DockerRootDir}}'`. Same filesystem today. If Docker's data root ever moves to its own volume,
  the alarm keeps reporting a healthy `/` while the Docker filesystem fills, and the `resources`
  list in the agent config must move with it. Adding a second mount point there also turns the
  alarmed rollup into an average across filesystems, which would mask a full root disk — pin a
  `path` dimension on the alarms if that day comes.

**When an alarm fires:**

```bash
docker system df                      # how much is reclaimable, and in what
docker images --format '{{.Tag}}' | grep '^sha-' | sort | uniq -c | sort -rn
docker image prune -af                # what deploy-backend.sh does for itself below the floor
```

If stale `sha-` tags are piling up, the deploy's own reclaim is not keeping pace — check
`DOCKER_PRUNE_INTERVAL_HOURS` and the tail of the last deploy log. If the disk is already full, note
that SSM will return exit 1 with **empty** output for every command: expand the volume and reboot so
cloud-init's `growpart` extends the filesystem, because a bigger volume alone changes nothing the OS
can see. Full history in #1862.

**Applying a config change:** edit `deployment/alpha/cloudwatch-agent-config.json` and merge. The
`sync-alpha-config` workflow ships it to the box and runs the installer, which fails the workflow if
the agent does not come back running, configured and enabled. That step runs **last**, after the
containers are recreated, so a monitoring failure can never block delivery of a merged compose
change.

The installer validates the config is well-formed JSON before applying it, but that is a syntax
check only: a typo'd measurement name is well-formed and gets rejected later by the agent itself.
Note also that applying restarts the agent even when the file is unchanged, costing one 60s
datapoint inside a 300s alarm period.

To run it by hand on the box:

```bash
sudo bash /opt/durion/alpha/scripts/install-cloudwatch-agent.sh
```

**Verifying it is actually publishing.** The installer checks the agent is running, configured and
enabled at boot, but it cannot confirm metrics arrive: that needs `cloudwatch:GetMetricStatistics`,
which the instance role does not carry. An agent whose role is missing `CloudWatchAgentServerPolicy`
passes every check and publishes nothing, so verify with the metric, from an operator shell:

```bash
aws cloudwatch get-metric-statistics --region us-east-1 --namespace CWAgent \
  --metric-name disk_free --dimensions Name=InstanceId,Value=<instance-id> \
  --start-time "$(python3 -c 'import datetime;print((datetime.datetime.now(datetime.timezone.utc)-datetime.timedelta(minutes=20)).strftime("%Y-%m-%dT%H:%M:%SZ"))')" \
  --end-time "$(python3 -c 'import datetime;print(datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))')" \
  --period 300 --statistics Average
```

Also confirm the topic has a **confirmed** subscription. An SNS topic with none, or with one still
`PendingConfirmation`, reproduces "nothing alarmed" exactly:

```bash
aws sns list-subscriptions-by-topic --region us-east-1 \
  --topic-arn arn:aws:sns:us-east-1:288757602241:durion-alpha-alerts \
  --query 'Subscriptions[].[Endpoint,SubscriptionArn]' --output text
```

#### Rebuilding the alpha host

This repo has no infrastructure-as-code, so the AWS side of the above is **not** recreated by any
pipeline. A rebuilt instance publishes no host metrics, and the two alarms stay pinned to the old
instance id — where the warning alarm, treating missing data as breaching, fires forever against a
dead box while the new one goes unwatched.

Order matters here. Repointing the alarms first would leave you with alarms watching a host that has
no agent on it, which is worse than the gap it replaced.

```bash
INSTANCE=i-xxxxxxxxxxxxxxxxx
BUCKET=<ALPHA_DEPLOY_BUCKET>
TOPIC=arn:aws:sns:us-east-1:288757602241:durion-alpha-alerts

# 0. Point the repo at the new box FIRST. Every workflow below sends to this variable, so until it
#    is updated `sync-alpha-config` and the deploy both still talk to the dead instance.
gh variable set ALPHA_EC2_INSTANCE_ID --body "$INSTANCE"
#    Confirm the new instance carries the EC2-SSM-Role instance profile, or SSM cannot reach it:
aws ec2 describe-instances --region us-east-1 --instance-ids "$INSTANCE" \
  --query 'Reservations[0].Instances[0].IamInstanceProfile.Arn' --output text

# 1. The agent needs PutMetricData; AmazonSSMManagedInstanceCore does not grant it. Idempotent.
aws iam attach-role-policy --role-name EC2-SSM-Role \
  --policy-arn arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy

# 2. Bootstrap the installer from S3. A fresh box has nothing under /opt/durion, so this cannot
#    assume the on-box path — sync-alpha-config is what puts it there, and it has not run yet.
CMD=$(aws ssm send-command --region us-east-1 --instance-ids "$INSTANCE" \
  --document-name AWS-RunShellScript --timeout-seconds 2700 \
  --parameters "{\"commands\":[\"mkdir -p /opt/durion/alpha/scripts\",\"aws s3 cp s3://${BUCKET}/alpha/cloudwatch-agent-config.json /opt/durion/alpha/cloudwatch-agent-config.json\",\"aws s3 cp s3://${BUCKET}/alpha/scripts/install-cloudwatch-agent.sh /opt/durion/alpha/scripts/install-cloudwatch-agent.sh\",\"bash /opt/durion/alpha/scripts/install-cloudwatch-agent.sh\"]}" \
  --query Command.CommandId --output text)

#    Read the result. The installer's whole value is that it dies loudly on each condition that
#    would leave the box unwatched, and send-command discards every one of those signals.
sleep 60
aws ssm get-command-invocation --region us-east-1 --command-id "$CMD" --instance-id "$INSTANCE" \
  --query '[Status,StandardOutputContent,StandardErrorContent]' --output text

# 3. Only once step 2 reports Success, repoint both alarms at the new instance.
aws cloudwatch put-metric-alarm --region us-east-1 --alarm-name alpha-root-disk-low \
  --namespace CWAgent --metric-name disk_free --dimensions Name=InstanceId,Value="$INSTANCE" \
  --statistic Minimum --period 300 --evaluation-periods 3 --datapoints-to-alarm 3 \
  --threshold 42949672960 --comparison-operator LessThanThreshold \
  --treat-missing-data breaching --alarm-actions "$TOPIC" --ok-actions "$TOPIC"

aws cloudwatch put-metric-alarm --region us-east-1 --alarm-name alpha-root-disk-critical \
  --namespace CWAgent --metric-name disk_free --dimensions Name=InstanceId,Value="$INSTANCE" \
  --statistic Minimum --period 300 --evaluation-periods 2 --datapoints-to-alarm 2 \
  --threshold 16106127360 --comparison-operator LessThanThreshold \
  --treat-missing-data missing --alarm-actions "$TOPIC" --ok-actions "$TOPIC"
```

Then verify with the `get-metric-statistics` and `list-subscriptions-by-topic` commands above before
considering the rebuild done. Step 0 needs `gh` authenticated against this repo with variable-write
access, and step 1 needs `iam:AttachRolePolicy`; if you hold neither, they are the two things to
hand to someone who does.

Note that `build-push-ecr.yml` does **not** ship the agent files — only `sync-alpha-config.yml`
does. A rebuilt box that receives nothing but code deploys therefore stays unwatched until either
this checklist is run or something under `deployment/alpha/` is merged.

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

A caller's authorities are resolved from the database along one chain, and only this chain:

```
users -> role_assignments -> roles -> role_permissions -> permissions
```

`RoleAuthorityService` reads it at login, `JwtService` encodes the result into the `perm_bits`
claim, and the gateway decodes `perm_bits` into `X-Authorities` for downstream `@PreAuthorize`
checks. There is no hardcoded role-to-authority map: a role grants exactly what
`role_permissions` holds, and a role with no grants yields no permissions at all.

Do not confuse the two tables:

| Table | Meaning |
| --- | --- |
| `role_permissions` | **role → permission** grants — what a role can do |
| `role_assignments` | **user → role**, effective-dated (`effective_start_date`, `effective_end_date`, `revoked_at`) — the only store of a user's roles (ADR-0061 amendment, 2026-09-09, #1914 phase 2; the undated `user_roles` join table it used to sit alongside was migrated and dropped); feeds every decision point through `EffectiveGrantResolver`. Carries no location scope (that is `roles.location_scope` plus the pos-people staffing assignment, ADR-0061 §1) and does **not** narrow a JWT |

### Provisioning Role Grants

Baseline grants for the canonical roles ship as a repeatable Flyway migration,
`pos-security-service/src/main/resources/db/migration/R__seed_role_permissions.sql`. It applies
in every environment where Flyway runs, resolves roles and permissions by name rather than by
UUID, and is idempotent — re-running it inserts nothing new.

**To change the baseline:** edit the seed file. Flyway re-applies a repeatable migration when
its checksum changes, so the grants land on the next pos-security-service startup. The seed only
inserts, so it can add a capability but never revoke one; to remove a grant, use the admin API
below or write a versioned migration.

**To adjust one environment without touching the baseline:** use the role-permission admin API
(next section). Those grants survive seed re-runs.

**If the migration aborts** with `role_permissions baseline references unknown roles: ...` or
`... unknown permissions: ...`, a name in the baseline no longer resolves — usually a role
renamed through the admin API, or a permission renamed in a `permissions.yaml` manifest. This is
deliberate: a silent name mismatch would under-grant authority with no signal. Reconcile the
name, then restart.

`SYSTEM_ADMINISTRATOR` is scoped to the security/admin surface only and is **not** a superuser;
it does not acquire newly registered permissions automatically. `ADMIN` is the all-domain role.
See `pos-security-service/README.md` for the full role policy.

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

Assignments are effective-dated only. Location reach is not set per assignment: it is the
role's `location_scope` (ADR-0061 §1) resolved against the user's pos-people staffing
assignment when the token is issued.

**Open-ended:**

```bash
POST /api/roles/assignments
Content-Type: application/json
Authorization: Bearer {JWT_TOKEN}

{
  "userId": 123,
  "roleId": 1,
  "effectiveStartDate": "2026-01-13"
}
```

**Bounded window:**

```bash
POST /api/roles/assignments
Content-Type: application/json
Authorization: Bearer {JWT_TOKEN}

{
  "userId": 123,
  "roleId": 2,
  "effectiveStartDate": "2026-01-13",
  "effectiveEndDate": "2026-12-31"
}
```

### Checking Permissions

There is no per-user `check-permission` probe: location-scoped decisions are made by the owning
service from the token's `loc_fin_bits` / `loc_oth_bits` / `loc_scope` claims (ADR-0061 §3).

```bash
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

## Tenant Provisioning

### Activating the first administrator (ADR-0062 §7, WS2b-3)

`POST /tenant/v1/tenants` in `pos-tenant` publishes `tenant.created`; `pos-security-service` copies
the platform role template into the new tenant, creates the first administrator named by
`initialAdminEmail` on `ADMIN`, and answers `tenant.provisioned`, which moves the tenant to
`ACTIVE`. **That administrator cannot sign in yet**: no credential rides on any event. The account
is created credential-expired and marked `awaiting_activation` behind a discarded random password, and a
login attempt answers the same 401 `INVALID_CREDENTIALS` as any wrong password. The first credential is set through an
operator-delivered activation token (decided 2026-09-10):

```bash
# 1. As a platform operator (PLATFORM_ADMIN in the platform tenant, e.g. admin.platform), find the
#    administrator's user id in the new tenant. The platform token carries tid = platform tenant.
TENANT_ID=<id from POST /tenant/v1/tenants>
USER_ID=<id of initialAdminEmail in that tenant>

# 2. Mint the token. The response is the only copy: only its SHA-256 is stored.
curl -sS -X POST "https://<gateway>/security-service/v1/platform/tenants/$TENANT_ID/administrators/$USER_ID/activation-token" \
  -H "Authorization: Bearer $PLATFORM_ACCESS_TOKEN" -H "X-API-Version: 1"
# → 201 {"token":"<43 URL-safe chars>","expiresAt":"2026-09-13T12:00:00Z"}

# 3. Hand the token to the administrator out of band (no e-mail is sent). Within 72 hours they
#    exchange it, unauthenticated, for their password:
curl -sS -X POST "https://<gateway>/security-service/v1/auth/activate" -H "X-API-Version: 1" \
  -H "Content-Type: application/json" -d '{"token":"<token>","newPassword":"<their password>"}'
# → 204; then POST /v1/auth/login with the tenant's slug works.
```

Rules and refusals:

| Situation | Answer |
| --- | --- |
| Token lost, expired or the administrator never activated | Mint again (step 2). Each mint closes every earlier open token for that user; there is no way to read a token back. |
| Token reused, expired (72 h) or unknown | 401 `ACTIVATION_TOKEN_INVALID` — one code on purpose, so nothing about the account or the token's history leaks to an unauthenticated caller. |
| Caller holds `platform:tenant:provision` but is bound to a tenant other than the platform tenant | 403 `PLATFORM_TENANT_REQUIRED`. Only `PLATFORM_ADMIN` in the platform tenant holds `platform:*` (`R__seed_tenant_template.sql`); never grant it to a tenant role. |
| Caller lacks the permission | 403 `FORBIDDEN`. |
| `USER_ID` is not a user of `TENANT_ID` | 404 `USER_NOT_FOUND`. |
| The user is not awaiting activation (already activated, password set through `PUT /v1/users/{id}`, or any account provisioning did not create — including one whose credentials an administrator expired) | 409 `USER_NOT_AWAITING_ACTIVATION`. Only the account carrying the explicit `users.awaiting_activation` marker, which provisioning alone sets, can be activated with a token; a live account's password is never overwritten this way. |
| Administrator wants a new password later | The ordinary account-state / password paths; the activation token is for the first credential only. The same token shape is the basis of the coming e-mail reset. |

Audit: every mint writes an `AdministratorActivationTokenMinted` audit event on the user (actor,
expiry, number of earlier tokens closed) and an INFO log line; every activation logs the user, tenant
and token id. The token itself is never logged.

### Bulk loading into a tenant (ADR-0062, WS8)

Every `pos-bulk-loader` job loads into exactly one tenant, named by `tenantId` on
`POST /bulk-loader/bulk-jobs` (`scripts/seed-alpha.py --tenant-id`). The loader binds that tenant around the
job's create and around the whole batch run, so the job row, its audit and mapping rows, and every call it
makes to the owning services (`X-Tenant-Id` on each `/bulk-ingest` and lookup call) land in that tenant.

```bash
# Tenant data (the alpha packs): a token of that tenant, naming that tenant.
scripts/seed-alpha.py --gateway https://<gateway> --token "$SEED_BEARER_TOKEN" \
    --tenant-id 01900000-0000-7000-8000-000000000001

# Platform data (the role template): a PLATFORM_ADMIN token, naming the platform tenant. The
# platform tenant has no locations, and a job needs one, so the location is given explicitly:
# the role and grant ingests carry it along and ignore it, so the nil UUID will do.
scripts/seed-alpha.py --gateway https://<gateway> --token "$PLATFORM_ACCESS_TOKEN" \
    --tenant-id 01900000-0000-7000-8000-000000000000 \
    --location-id 00000000-0000-0000-0000-000000000000 \
    --only security/roles.csv --only security/role-permissions.csv
```

The driver requires `--tenant-id` to be the token's own tenant (its `tid` claim, which is also
the default when the flag is omitted), refuses the platform tenant for any pack but the two
security role packs, and insists on `--location-id` there. The API itself is wider (a platform
caller may create a job in any active tenant); the rows below say what each answer means.

| Situation | Answer |
| --- | --- |
| `tenantId` omitted | 400 `BULK_JOB_TENANT_REQUIRED`, unless the loader's transitional default tenant (`pos.tenancy.default-tenant-id`) is configured: the job then loads into the default and the loader logs a WARN. Name the tenant; the fallback goes away with the default. |
| `tenantId` is not an active tenant of the cell | 400 `BULK_JOB_TENANT_UNKNOWN`. The loader asks its `TenantRegistry`: `pos.tenancy.tenants`, else the default tenant, or pos-tenant's list with `pos.tenancy.registry.mode=REMOTE`. The platform tenant is always allowed. |
| Caller's token is bound to tenant A, `tenantId` is B | 403 `BULK_JOB_TENANT_FORBIDDEN`. Only a caller bound to the platform tenant (`PLATFORM_ADMIN`) loads into another tenant. |
| A platform operator created a job in tenant B and wants to upload, process or poll it | Job endpoints are tenant-scoped reads: the job is visible only under B's binding. Continue with a token bound to B (the tenant's own administrator, or the platform impersonation token once WS2b-4 lands). `seed-alpha.py` refuses this mode up front for that reason. |
| `roles.csv` loaded into the platform tenant | The roles become the platform role template (`template_key` = name); see "Reconciling the role template" to push them to existing tenants. |

The job's tenant is recorded as the non-identifying `tenantId` batch parameter and on every log line (MDC
`tenantId`), and is returned as `tenantId` on the job.

### Reconciling the role template (ADR-0062 §6, WS8)

Provisioning copies the platform role template once, on `tenant.created`. When the template grows
afterwards — a platform bulk load of `roles.csv` / `role-permissions.csv` into the platform tenant, a grant
added to a template role, a role created in the platform tenant and promoted — existing tenants are brought
up to it explicitly, one tenant per call:

```bash
# As a platform operator (PLATFORM_ADMIN in the platform tenant, platform:tenant:provision).
curl -sS -X POST "https://<gateway>/security-service/v1/platform/tenants/$TENANT_ID/roles/reconcile-template" \
  -H "Authorization: Bearer $PLATFORM_ACCESS_TOKEN" -H "X-API-Version: 1"
# → 200 {"tenantId":"...","rolesCreated":["WARRANTY_CLERK"],
#        "grantsAdded":[{"role":"SHOP_MANAGER","permission":"warranty:claim:view"}],
#        "templateKeysAssigned":["DISPATCHER"]}
```

What it does, per template role: missing in the tenant → created with the template's description, persona,
location scope and grants, exactly as provisioning would have; present → keeps every tenant-local grant and
gains the grants the template carries that it lacks (union, never removal; `role_permissions.granted_by =
role-template-reconcile`), and gets `template_key` when it had none. An existing role's description, persona
and scope are left alone (they may be tenant edits). Idempotent: run it again and every list is empty. A
template grant naming a permission the catalog has not registered yet is skipped with a WARN in
pos-security-service's log; re-run after the owning module has registered it. Refusals: 403
`PLATFORM_TENANT_REQUIRED` for a caller bound to any tenant but the platform tenant, 404 `TENANT_NOT_FOUND`
for a tenant pos-security-service's `ext_tenant` replica does not hold.

Promoting a bulk-loaded role to the template: a role loaded into the *platform* tenant (`roles.csv` with a
`PLATFORM_ADMIN` token and `--tenant-id` = the platform tenant) joins the template on load. A role that was
loaded into a tenant instead is not template data; load it into the platform tenant as well (the loader marks
an already-present platform role rather than refusing it), then reconcile each tenant that should receive it.
Grants come the same way: `role-permissions.csv` into the platform tenant, then reconcile.

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

### Adding a permission: one command (#1848)

A permission needs four things to work end to end: an annotation, a bit in the catalog, a
`permissions.yaml` entry, and a **grant**. The first three come from
`scripts/generate-permissions.sh --sync`. The fourth used to be a hand edit of two more files,
and forgetting it failed CI in two separate jobs after the fact — `audit-rbac.py --check`
(`required_ungranted` / `required_no_bit` / `unreachable_op_count`) in PR Checks, and
`RoleBaselineDriftTest` in the reactor build. `--sync` now writes the grant too:

```bash
# 1. annotate the endpoint
@PreAuthorize("hasAuthority('catalog:tread_design:resolve')")

# 2. one command: bit + catalogs + manifest + both grant sources
scripts/generate-permissions.sh --sync --grant ADMIN

# 3. verify locally exactly as CI will
scripts/generate-permissions.sh --sync --check
python3 scripts/audit-rbac.py --check
./mvnw -pl pos-security-service -am -Dtest='RoleBaselineDriftTest,RolePermissionBaselineTest' test
```

What `--sync` writes, beyond the three catalogs:

| File | What lands |
| --- | --- |
| `pos-security-service/.../db/migration/R__seed_role_permissions.sql` | the section-2 `permissions` row (name, domain, resource, action, freshly assigned bit); the section-3 role grant; the section-4 self-check entry |
| `scripts/fixtures/seed/alpha/security/role-permissions.csv` | the permission added to the target role's `;`-separated list |

Rules the command follows, and why:

- **`--grant ROLE` is repeatable**; the default is `ADMIN`, the all-domain role, so a permission
  granted nowhere else is still reachable by an administrator and widens nothing an operator role
  can reach. A permission whose owning module's `permissions.yaml` entry carries a `grantTo:` list
  uses that instead — a per-permission decision reviewed in the module that owns it beats a
  run-wide flag.
- **Only the roles Flyway still creates reach the SQL** (#1613 D8): `ADMIN`, `CONTROLLER`,
  `DISPATCHER`, `SELF_SERVICE_CUSTOMER`, `SHOP_MANAGER`, `SYSTEM_ADMINISTRATOR`. A grant to any
  other role is written to the baseline CSV alone — the seed's own section-4 guard raises on a
  role it cannot resolve, and `RoleBaselineDriftTest` fails the build on one it can. The section-2
  permission row is written either way, because `role_permissions` is foreign-keyed to
  `permissions` and the CSV loader resolves its grants by name against the same table.
- **`--grant SYSTEM_ADMINISTRATOR` is refused.** That role's seed block is duplicated in
  `V31__revoke_system_administrator_out_of_band_grants.sql`, which this script does not edit;
  widening it stays a deliberate hand edit of both files.
- **A role with no row in the baseline CSV is refused**, rather than invented: the role set is
  pinned by `RoleBaselineDriftTest`, so an unknown name is a typo, not a new role.
- **Re-running changes nothing.** Existing rows are never reordered — the seed carries a few
  historical deviations from a strict sort, and re-sorting to "fix" them would bury a one-line
  change in a 500-line diff. Each new row is inserted at its sorted position instead.
- **`--sync --check` fails** when a permission that has a bit and a `@PreAuthorize` is granted in
  neither source. (A catalog code no annotation names is dead weight rather than a broken
  endpoint; `audit-rbac.py`'s informational `catalog_dead` is where that is triaged.)

**A catalog bump is a fleet-coordinated deploy.** Adding a bit increments `CATALOG_VERSION` in
`PermissionCode`, `GatewayPermissionCatalog` and `DownstreamPermissionCatalog`. JWTs carry
`perm_bits` plus `perm_ver`, and the downstream check on `perm_ver` is strict: a token minted
against the old version is rejected once the gateway runs the new one. Ship pos-security-service,
pos-api-gateway and every service embedding `pos-security-common` together, and expect issued
tokens to need re-minting — do not roll one service forward on its own.

### Location-scope decisions (`location-scope.yaml`)

`@PreAuthorize` answers "may this caller do X"; it does not answer "may they do it *here*". Under
ADR-0061 the owning service decides that at every endpoint that takes a caller-supplied
`locationId` (path, query or request body), from the token's `loc_fin_bits` / `loc_oth_bits` /
`loc_scope` claims via `SecurityContextHelper.locationScope()`. Each module records what it decided
for each such operation in `<module>/location-scope.yaml` (module root, beside `openapi.yaml`), and
`scripts/audit-rbac.py --check` (the "Check RBAC authorization drift" step in `pr-checks.yml`)
fails the build when the file and the code disagree. Rollout: #1872.

**Format** — flat, one entry per operation, every value on one line (the checker is a regex parser,
not PyYAML; folded `>` / `|` scalars are rejected):

```yaml
# Location-scope decisions for pos-inventory (ADR-0061, #1872).
decisions:
  - operation: StockMovementController.createAdjustmentRequest   # SimpleClassName.methodName
    shape: gate                                                  # gate | narrow | unscoped
    permission: inventory:adjustment:create                      # required for gate / narrow
    reason: locationId names the site the adjustment is raised at; denied outside the caller's reach.
  - operation: BackorderController.listBackorders
    shape: narrow
    permission: inventory:backorder:view
    reason: locationId is an optional filter; a scoped caller with no filter sees their reach only.
  - operation: CatalogBulkIngestController.bulkIngest
    shape: unscoped
    reason: ADMIN-only bulk load; the location is a payload default, not an access boundary.
```

| Shape | Meaning |
| --- | --- |
| `gate` | `locationId` names the resource acted on: `scope.require(permission, locationId)` → 403 `LOCATION_SCOPE_DENIED` outside the caller's reach |
| `narrow` | `locationId` is an optional filter on a list/search/report: gate it when given, otherwise restrict the query to `scope.reach(permission)` |
| `unscoped` | deliberately no check; the `reason` says why (bulk load, no location-private data, or deferred with a tracking issue) |

**CI codes** (never baselined — all three must be zero):

| Code | Fires when |
| --- | --- |
| `location_scope_undecided` | a controller operation takes a `locationId` and the module's file has no entry for it (or the file is missing) |
| `location_scope_stale` | an entry names an operation that no longer exists in the module — an entry for a sibling endpoint that takes no `locationId` (e.g. the by-id detail you gated beside a list) is allowed as long as the `Class.method` exists in one of the module's controllers |
| `location_scope_invalid` | `shape` not `gate`/`narrow`/`unscoped`, `gate`/`narrow` without `permission`, any entry without `reason`, a duplicate operation, or a file the parser cannot read |
| `location_scope_alternates` | a location-scope call passes a permission the endpoint reaching it does not require, or an endpoint takes a scope decision with no `@PreAuthorize` and no authority check at all (#1890) |

`location_scope_summary` (operations found / decided; entries per shape, per module) is printed for
information only.

**Why the alternates must match (`location_scope_alternates`, #1890).** `LocationScope` and
`LocationScopeService` decide from the alternates the caller *holds*: a caller who holds none of
them takes neither decision — `require` does not deny and `reachOf`/`reach` does not narrow (#1889).
That is safe only because every HTTP path into a scope call is gated on the same alternates, so
passing the gate guarantees at least one is held and the empty case cannot arise from a request.
An endpoint whose `@PreAuthorize` names `a, b` while its scope call passes `c` would therefore stop
narrowing silently, and one with no `@PreAuthorize` at all would return everything rather than
nothing — a scope check against a permission the endpoint does not actually require is not a check.

The gate holds the alternates to that: every permission named at a scope call must be one the
reaching endpoint requires, either in its `@PreAuthorize` or through an explicit in-body authority
check that denies (`WipController.listWip` gates its `workorder:wip:view_all_locations` widening
flag that way — the annotation cannot name it without letting a caller in who holds only that).
Calls in service and helper methods carry no annotation, so the checker resolves the controller
that reaches them through a module-local call graph rather than exempting them; permissions passed
as arguments into that call count as named, which covers the forwarding helpers
(`BayController.requireInScope`, `AppointmentsController.requireScopeOnStoredLocation`).

Fixing a report means making the two agree: either widen the `@PreAuthorize` to accept the
alternate the scope call uses (a contract change — regenerate the spec and run `API Artifacts
Sync`), or pass the alternates the endpoint actually requires. Never silence it by dropping the
scope call.

**Adding a location-parameterised endpoint:** decide the shape, implement it (see pos-workorder
`WipController` for a gate and pos-people `TimeEntryServiceImpl` for a narrow), document the 403
`LOCATION_SCOPE_DENIED` response on the operation, then add the entry to the module's
`location-scope.yaml` and run `python3 scripts/audit-rbac.py --check` locally. Renaming or deleting
the method means updating or removing the entry in the same change. A module adopting `gate` or
`narrow` for the first time also needs a `LocationAncestorResolver` bean (its location replica's
hierarchy service) — without one every scoped caller is denied (fail closed).

---

## Domain Events (Kafka, ADR-0044)

Module-to-module communication flows over Kafka domain topics (`{domain}.events.v1` facts,
`{domain}.commands.v1` commands). Contracts live in `pos-domain-events`; the full policy is the
canonical ADR-0044 (`durion/docs/adr/0044-platform-event-only-domain-walls.adr.md`), enforced by
`pos-archunit` `DomainWallsTest`.

### Local broker

`docker-compose up -d kafka` starts a single-node KRaft broker (`apache/kafka`). Services reach it
at `kafka:29092` inside the compose network (host tools at `localhost:9092`). Kafka features remain
opt-in per module (e.g. `WORKORDER_KAFKA_ENABLED=true`, `pos.customer.kafka.enabled=true`,
`POS_INVOICE_KAFKA_ENABLED=true`, `pos.accounting.kafka.enabled=true`) until
the Phase 0.4 tier-1 flip.

### Warranty events rollout (#927)

The warranty fact feed (`warranty.events.v1`) and its two consumers are live. Required flags per
environment (already defaulted `true` in the root `docker-compose.yml` and set in
`deployment/alpha/docker-compose.prod.yml`; export explicitly anywhere else):

- `POS_WARRANTY_KAFKA_ENABLED=true` — pos-warranty publishes all six `warranty.*` facts.
- `POS_ACCOUNTING_KAFKA_ENABLED=true` — pos-accounting materializes
  `warranty.reimbursement.submitted/.resolved` into `warranty_reimbursement_expectation`
  (consumer group `pos-accounting-warranty-events`).
- `POS_INVENTORY_KAFKA_ENABLED=true` — pos-inventory materializes
  `warranty.part-return.requested/.shipped` into `warranty_part_return_hold`
  (consumer group `pos-inventory-warranty-events`).

Note the module flags are module-wide: enabling them also turns on those modules' other
listeners/publishers (accounting customer/invoice replicas, inventory location/workorder replicas
and outbox). `warranty.claim.settled` / `warranty.claim.snapshot` have no consumer yet — both
consumers record their eventIds and skip them.

### Transactional outbox (producers)

Producers write events to their `event_outbox` table in the business transaction; a scheduled
publisher drains to Kafka (at-least-once). Operational signals:

- Metrics (per module, whenever its Kafka flag is on): `<domain>.outbox.published` /
  `<domain>.outbox.publish.failures` counters, plus `<domain>.outbox.pending` and
  `<domain>.outbox.oldest.age.seconds` gauges (`OutboxHealthContributor`, shared in `pos-events`;
  registered by each module's `OutboxHealthConfig` — #1458; this includes `pos-supplier`, whose
  outbox lives in `supplier_event_outbox` rather than `event_outbox`). A growing
  published/failures gap or a growing oldest-age means the drain is failing.
- Health: `/actuator/health` carries an `outbox` component with `drainState`
  (`drained` / `draining` / `stalled-never-attempted` / `stalled-retrying`), the head row's age,
  and `attempts`. `last_error` text is deliberately **not** exposed there — some profiles serve
  health details anonymously (`show-details: always`), and raw broker/serializer errors don't
  belong on an unauthenticated endpoint; read it with the outbox SQL below.
  The component is **always UP by design** — a stalled drain stales downstream replicas but does not stop
  the service, and a DOWN here would restart-loop containers via the compose healthcheck. Alert
  on the gauges, read the details when diagnosing. Note there is deliberately **no broker-ping
  health indicator**: Spring Boot ships none, and the two failure modes a ping would miss
  (publisher not scheduled at all, poison row retrying forever) are exactly the ones
  `drainState` distinguishes; broker/consumer state is `kafka-exporter`'s job.
- `SELECT count(*) FROM event_outbox WHERE published_at IS NULL` — sustained growth means the
  broker is unreachable or the publisher is stopped; check `attempts`/`last_error` on stuck rows.
- The publisher halts its batch at the first failure to preserve order — one poisoned/oversized
  record blocks the drain; inspect the oldest unpublished row first
  (`stalled-never-attempted` with `attempts = 0` means the publisher is not running at all —
  check the module's Kafka flag actually reached the container, see #1456/#1457).

### Dashboards and alerts (#838)

Grafana dashboard **"Domain Events (ADR-0044)"** (provisioned from
`observability/grafana/provisioning/dashboards/json/domain-events.json`) charts outbox
backlog/age, publish/failure rates, consumer lag, DLQ depth, replica drift, and manifest activity.
`kafka-exporter` (compose service, :9308) supplies topic depth and consumer-group lag.

Provisioned alert thresholds (dashboard-only delivery in alpha):

| Signal | Warn | Critical |
|---|---|---|
| Oldest unpublished outbox row (every outbox service, per `service` label — #1458) | > 5 min | > 15 min |
| Unpublished outbox backlog (every outbox service) | > 1000 rows for 10 min | — |
| Consumer group lag | > 100 records for 5 min | — |
| DLQ message | any | — |
| `replica_drift_total` | any increase | — |
| Manifest silence (`workorder.manifest.v1`) | > 2 h (2× window) | — |

### Retention (#838)

- Kafka topics (delete policy, provisioned by the `kafka-topic-init` compose job): `*.events.v1`
  and `*.commands.v1` 7 d; `*.manifest.v1` 3 d; `*.dlq` 30 d. 1 partition per topic on the
  single-node broker.
- That job's topic list is generated by `scripts/generate-kafka-topics.py` from the
  `@KafkaListener` defaults and `*-topic` properties, with a `.dlq` per consumed topic; run it
  with `--apply` after adding a listener and commit `docker-compose.yml`. The list was previously
  hand-written and covered 14 of ~35 topics, so 30 DLQs were running at the broker default of 7 d
  rather than the 30 d above (#1578, #1579). Topics already provisioned at the wrong retention are
  corrected in place on the next deploy — the job alters any topic whose config differs.
- `event_outbox` table: published rows are purged after **90 days** (`OutboxPurgeJob`, nightly,
  `workorder.outbox.retention-days`). Replay history equals this retention — a window older than
  90 days can no longer be re-emitted. Unpublished rows are never purged.

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

#### pos-customer: seeding a party-identity replica

pos-customer publishes party identity as `customer.party.updated`. Seeding a replica of it is one
paged, resumable call — pass the previous response's `nextAfterId` until it comes back
`complete: true` — which **refuses with 409** when `pos.customer.kafka.enabled` is off, rather than
reporting a page of facts nobody received.

```bash
# Parties (customer.party.updated) — #1893
curl -X POST "https://<gateway>/customer/v1/crm/accounts/facts/replay?limit=500" \
  -H "Authorization: Bearer $TOKEN" -H "X-API-Version: 1"
```

The replay covers both concrete party types (commercial and person) under a single cursor, so the
`nextAfterId` from one page is the only thing the next page needs.

**The generic outbox replay does NOT seed this.** `customer.outbox.replay-requested` re-queues rows
that already exist in `event_outbox`, within `pos.customer.outbox.replay.max-lookback` (30 days by
default). A party nobody has edited since the consumer's replica was created has no outbox row to
re-queue, so its identity has never been published at all and no replay can reach it. That is a
different failure from drift, and it needs the rebuild-from-state replay above — the same
distinction as pos-location's capability columns under "Issue #1514" below.

Consumers of `customer.events.v1` party facts, and what a cold replica costs them:

| Consumer | Replica | If not seeded |
|---|---|---|
| `pos-accounting` | `ext_customer_party` | `customerDisplayName` / `customerReference` are null on every credit-memo response, so the Credit Memo screens have no customer to show (#1893) |
| `pos-invoice`, `pos-workorder`, `pos-shop-manager` | `ext_customer_party` | The party UUID resolves to no name, so screens and documents fall back to a placeholder |

Verify on the consumer side, not just the producer — an empty replica is the whole symptom:

```sql
-- pos-accounting. Zero rows here is why credit memos show no customer name.
SELECT count(*) FROM ext_customer_party;
```

A deletion cannot be replayed — a deleted party is gone, so its tombstone exists only in the live
stream. A freshly seeded replica therefore holds what pos-customer currently has, which is what
display resolution needs; it will not learn about parties removed before the seed.

**Seeded environments need this run explicitly.** Fixture packs that write rows straight into
`commercial_party` / `person_party` bypass `CustomerFactPublisher` entirely, so no fact is ever
emitted for a seeded party and every downstream replica stays empty however long the environment
runs. Run the replay above after seeding, then re-check the consumer count.

#### pos-catalog: seeding a catalog replica

pos-catalog publishes products and services as separate facts, so seeding a catalog replica is two
calls, not one. Both are paged and resumable — pass the previous response's `nextAfterId` until it
comes back `complete: true` — and both **refuse with 409** when `pos.catalog.kafka.enabled` is off,
rather than reporting a page of facts nobody received.

```bash
# Products (catalog.product.updated) — #1309
curl -X POST "https://<gateway>/catalog/v1/products/facts/replay?limit=500" \
  -H "Authorization: Bearer $TOKEN" -H "X-API-Version: 1"

# Services (catalog.service.updated) — #1306
curl -X POST "https://<gateway>/catalog/v1/catalog-items/services/facts/replay?limit=500" \
  -H "Authorization: Bearer $TOKEN" -H "X-API-Version: 1"
```

Consumers of `catalog.events.v1`, and what a cold replica costs them:

| Consumer | Replica | If not seeded |
|---|---|---|
| `pos-marketing` | `ext_catalog` (products **and** services) | A campaign's `catalogFocusRef` is not verified for a kind with no rows, and blocks scheduling for one the replica knows partially — run **both** replays |
| `pos-warranty` | `ext_catalog` (products) | Candidate-line product lookup and warranty eligibility have no manufacturer or warranty terms to read |
| `pos-inventory` | `ext_product_uom`, `ext_product` et al. | UoM conversions, tracking level and substitution membership are missing; since #1514, so are the product's category and subcategory, which putaway rules match on |
| `pos-supplier` | `ext_product_code` | PRICAT vendor lines match nothing and quarantine |

A deletion cannot be replayed — a deleted row is gone, so its tombstone exists only in the live
stream. A freshly seeded replica therefore holds what the catalog currently has, which is what
resolution needs; it will not learn about items removed before the seed.

#### Issue #1514: rehydrating the putaway replica columns

Category-based putaway matches a received line against the item's catalog category/subcategory and
against the destination bin's storage class. Both facts live on pos-inventory replicas, and
`V41__ext_replica_category_and_capability.sql` **adds the columns empty and does not backfill them**.
Until each owner republishes, category matching has nothing to match on:

| Replica column | Owner | State after V41 | Effect on putaway |
|---|---|---|---|
| `ext_product.category_id` / `subcategory_id` | pos-catalog | null | `SUBCATEGORY` and `CATEGORY` putaway rules match nothing; every line falls through to the terminal `ANY` rule |
| `ext_storage_location.storage_category_code`, `hazard_containment`, `allow_new_product` | pos-location | null | a null code reads as `GENERAL`, which accepts every catalog category, so the compatibility matrix stops discriminating destinations |

Nothing dead-ends in that window — the permissive-null resolution is deliberate, and the containment
gate still refuses a hazardous item at an uncontained destination because it keys on the *item's*
class rather than the destination's. What is lost is the routing precision the feature exists to add,
so rehydrate before relying on it.

**pos-catalog side — a paged replay is sufficient.** The product-fact replay rebuilds each payload
from the live entity through `CatalogFactPublisher`, so a replayed fact carries `subcategoryId` and
`subcategory` even though the original emission predated them. Run the products replay from
"pos-catalog: seeding a catalog replica" above; the services replay is not involved.

```bash
curl -X POST "https://<gateway>/catalog/v1/products/facts/replay?limit=500" \
  -H "Authorization: Bearer $TOKEN" -H "X-API-Version: 1"
```

**pos-location side — the generic outbox replay does NOT work here.** `location.outbox.replay-requested`
re-queues *already-serialized* outbox rows (`OutboxReplayServiceImpl` → `markForReplaySince`), so it
re-emits the payload as it was stored — without the capability fields, which did not exist when those
rows were written. The consumer's stale guard skips only a strictly-newer version, so such a replay
applies and writes the same nulls back. It is not a repair for this change.

The capability is (re)published by a fresh write through `StorageLocationService`. Declare each bin's
capability with a PATCH, which both sets the column and republishes the fact:

```bash
curl -X PATCH "https://<gateway>/location/locations/$SITE_ID/storage-locations/$STORAGE_LOCATION_ID" \
  -H "Authorization: Bearer $TOKEN" -H "X-API-Version: 1" \
  -H 'Content-Type: application/json' \
  -d '{"storageCategoryCode":"BATTERY_RACK","hazardContainment":true}'
```

Note that re-running the alpha fixture pack is **not** a substitute:
`scripts/seed-alpha.py run_storage_locations` skips any storage location whose name already exists at
the site, so it declares capabilities on newly created bins only. Existing bins need the PATCH above
(or a rebuild of the environment).

**Site defaults must also be declared, or the staging bins are unreachable (issue #1557).**
`StagingLocationResolver` resolves the receiving staging location as: the
`pos.inventory.receiving.staging-location-id` property, then the site's declared default from the
`location_ref` replica, then a hardcoded `00000000-0000-0000-0000-000000000002`. That last value is
not a row in any `storage_location` table, and putaway refuses any receipt not booked at whatever
the chain resolved — so with no site default, a receipt booked at the real `Staging Floor` is
refused with `RECEIPT_NOT_STAGED`. `scripts/fixtures/seed/alpha/location/site-defaults.csv` declares
them for every seeded site; on an environment seeded before that pack existed, declare them by hand:

```bash
curl -X PUT "https://<gateway>/location/locations/$SITE_ID/defaults" \
  -H "Authorization: Bearer $TOKEN" -H "X-API-Version: 1" \
  -H 'Content-Type: application/json' \
  -d '{"defaultStagingLocationId":"'$STAGING_ID'","defaultQuarantineLocationId":"'$QUARANTINE_ID'"}'
```

The two ids must differ and both must belong to the site. The write republishes the location fact,
which is what carries the defaults into pos-inventory's `location_ref` replica — so verify on the
consumer side, not just the producer:

```sql
-- pos-inventory. Both columns non-null for every site, or receiving is still broken there.
SELECT location_id, code, default_staging_location_id, default_quarantine_location_id FROM location_ref;
```

**A row entirely absent from `ext_storage_location` is a different fault** (issue #1554): it meant
the storage location was created without a fact being emitted, which only Flyway-seeded rows could
do. The location operational seed is deleted; storage locations enter exclusively through
`POST .../storage-locations` (the fixture pack or the API), which publishes the fact at creation, so
a fresh environment hydrates the replica as it seeds. On an environment that still carries
fact-less pre-#1554 rows, the same PATCH above (any field, even a no-op value) republishes the fact
and creates the missing replica row.

**Order matters only in one direction**: the destination side is what the compatibility matrix reads,
and the item side is what the rules match on. Neither blocks the other, so both can run
independently, but until *both* have run a receipt routes by the `ANY` rule to a `GENERAL`-reading
bin — which is exactly the pre-#1514 behaviour.

**Verifying:**

```sql
-- Before the replay this is 100%. After it, the remainder is the products that are genuinely
-- unclassified in pos-catalog — check a sample against the catalog before assuming the replay
-- is incomplete.
SELECT count(*) FILTER (WHERE category_id IS NULL) AS uncategorized, count(*) AS total
FROM ext_product;

**A non-zero `V16` Stage D count in pos-catalog is a replay trigger.** The catalog-side migration
(#1536) repairs products whose `category_id` contradicted their subcategory's parent. It deliberately
does **not** bump `updated_at` or the aggregate version on the rows it corrects, so pos-catalog emits
no `catalog.product.updated` fact for them — which means `ext_product` here keeps the *old, wrong*
category until those products are republished by other means. A replica that looks fully populated by
the query above can still be serving a contradicted category. If Stage D repaired any rows, run a
pos-catalog product fact replay before trusting category-based putaway, and before running the
SKU_CATEGORY cut-over audit below: a stale category name silently changes which config row a SKU
matches.

-- Should reach 0. The publisher resolves an undeclared capability to GENERAL before emitting, so
-- a null here means "no post-#1514 fact has been seen for this location", never "undeclared".
SELECT count(*) FROM ext_storage_location WHERE storage_category_code IS NULL;
```

`pos.inventory.sku-category.resolve-from-replica` is a **separate** flag and defaults **off**. It
gates only the `SkuCategoryProvider` SPI (costing-method and sourcing-strategy resolution), not
putaway, which reads the unconditional `SkuCategoryLookup`. Do not turn it on as part of this
rollout — enabling it makes the `SKU_CATEGORY` scope of `sku_cost_method_config` reachable for the
first time and would flip matching SKUs off `DEFAULT` costing at their next ledger posting. It is a
financial change and gets its own procedure, below.

#### Issue #1668: seeding the bay and mobile-unit replicas

pos-location began publishing `location.bay.updated` / `location.bay.deleted` and
`location.mobile-unit.updated` / `location.mobile-unit.deleted` on `location.events.v1` in #1668.
pos-workorder's dispatch board and pos-shop-manager's unit roster read those facts from `ext_bay`
and `ext_mobile_unit`. Both replicas are empty on arrival and stay empty for every bay and mobile
unit that existed before #1668 and has not been mutated since — the stream is forward-only, so an
untouched row emits nothing and the unit is invisible on both boards.

**The generic outbox replay does NOT work here.** `location.outbox.replay-requested` re-queues rows
that are already in `event_outbox`; pre-#1668 bays and mobile units have **no outbox history at
all**, so there is nothing for replay to re-send. Use the regenerate-from-state command instead —
it rebuilds each fact from the live entity:

```bash
# location.commands.v1 — aggregate: bay | mobile-unit | all (default all)
docker exec -i kafka-positivity /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic location.commands.v1 <<'EOF'
{"commandType":"location.fact-backfill.requested","payload":{"aggregate":"all","afterId":"<optional uuid>"}}
EOF
```

`payload.aggregate` selects which tables are walked; omitting it means `all`. An **unrecognised**
value backfills **nothing** rather than everything — a typo cannot trigger a full-estate walk, and
the listener logs a WARN naming the rejected value. `payload.afterId` is optional and is only used
to resume (below).

**A run is bounded and resumable.** It stops after `pos.location.fact-backfill.max-rows-per-run`
rows (default 20000), committing `pos.location.fact-backfill.page-size` rows (default 500) per
transaction. The bound exists because the walk executes on the Kafka command-listener thread: an
unbounded pass can exceed `max.poll.interval.ms`, and an evicted consumer never commits its offset,
so the command is redelivered and the whole backfill restarts in a loop. When a run hits the bound
it logs a WARN naming the resume cursor; re-send the same command with `payload.afterId` set to that
cursor, and repeat until no WARN appears.

**It is idempotent, so an overlapping resume is safe.** Consumers apply a fact whose version equals
the replica's and skip only a strictly-greater one, so re-running the backfill — in whole or over
a range already covered — repairs stale rows without duplicating them.

**It shares a consumer group with `location.outbox.replay-requested`.** A long backfill therefore
delays replay requests other modules publish (including the automatic drift-repair commands under
"Reconciliation manifests and drift detection"). Prefer off-peak runs, and use `aggregate` to scope
the walk to the aggregate that actually needs repair.

Verify on the consumer side, not the producer:

```sql
-- pos-workorder and pos-shop-manager. Both should match pos-location's own counts once the
-- backfill has drained; a persistent shortfall means the run stopped at its bound (check for the
-- resume WARN) or the consumer is lagging.
SELECT count(*) FROM ext_bay;
SELECT count(*) FROM ext_mobile_unit;
```

A mobile unit with no base location is withheld by the producer by design and will never appear in
either replica; see `pos-location/README.md` ("Published facts: bays and mobile units").

### SKU_CATEGORY costing and sourcing cut-over (#1535)

Enabling `pos.inventory.sku-category.resolve-from-replica` is not a configuration tweak. It makes an
already-authored `SKU_CATEGORY` row start deciding a SKU's **costing method** at its next ledger
posting, and it does so per SKU, staggered by whenever pos-catalog last republished each product —
silently, unless you do the work below first. It changes **sourcing** at the same time, where
`SKU_CATEGORY` is the *highest*-precedence step.

Run these in order. Steps 1–5 are safe to repeat; step 7 is the only one that changes behaviour.

1. **Confirm the catalog replica is populated.**

   ```sql
   SELECT count(*) FILTER (WHERE category_id IS NULL) AS uncategorized, count(*) AS total
   FROM ext_product;
   ```

   A large `uncategorized` count means the pos-catalog product replay is incomplete. Finish it
   first. If you flip the flag mid-replay the change lands staggered, arriving SKU by SKU as
   products trickle in — which is precisely the failure mode this procedure exists to prevent.

2. **Audit.** `GET /v1/inventory/valuation/methods/sku-category-impact` (`inventory:location:admin`).
   This works **with the flag off** — it reads the replica directly rather than through the gated
   SPI — which is the only moment the answer is actionable. Read three fields:

   - `impactedSkuCount` — SKUs whose costing method would actually change.
   - `impactedSkuWithCostStateCount` — of those, the ones that already carry opening values, i.e.
     the ones step 6 has to cover.
   - `categoriesWithNoReplicatedProducts` — configured category names matching no replicated
     product. This is usually a casing or spelling mismatch between a config's `scopeValue` and
     `ext_product.category_name`: matching is **exact and case-sensitive**, so such a row would
     silently never fire. Fix or retire these before reading the counts as final.
   - `categoriesWithUntrimmedScopeValue` — config rows whose `scopeValue` carries leading or trailing
     whitespace. These can never fire either, for a subtler reason: resolution compares the stored
     value verbatim against an already-trimmed category name, so the two can never be equal. The
     admin API trims on write, so these are seeded or hand-inserted rows. Re-upsert them through
     `PUT /v1/inventory/valuation/methods` to normalise.

   Finally, check `truncated`. If it is `true` the product scan hit
   `POS_INVENTORY_SKU_CATEGORY_IMPACT_SKU_CAP` (default 5000) and every row-derived count is a lower
   bound — raise the cap and re-run before using the report to decide anything.

3. **Note that sourcing is affected too.** `impactedSourcingSkus` lists SKUs whose sourcing strategy
   would start resolving from the `SKU_CATEGORY` step — which outranks `SITE` and `DEFAULT`, so it
   overrides deliberate per-site configuration. This is not a costing question and it needs its own
   sign-off. The report deliberately does not claim today's effective strategy: computing it needs a
   `SourcingSelection` (a site and a reference location), so the honest answer varies per site.

4. **Decide, per config row.** For each active `SKU_CATEGORY` row, one of two answers: keep it (and
   revalue the SKUs it covers, step 6), or retire it (step 5). There is no third option — leaving a
   row in place unrevalued means its SKUs change method with stale opening values.

5. **Deactivate what is not wanted.**
   `DELETE /v1/inventory/valuation/methods/{configId}` (`inventory:location:admin`). This is a soft
   delete: the row is deactivated, never removed, and a `DEACTIVATED` row is written to
   `cost_method_change_log`. Re-run step 2 afterwards; `impactedSkuCount` should fall.

6. **Revalue what is kept.** For each impacted SKU with `hasCostState = true`, run the J4
   revaluation: `POST /v1/inventory/valuation/revaluations` (`inventory:valuation:adjust`) with
   `stockItemId`, `reason`, and **exactly one of** `newUnitCost` or `costDelta` — supplying both, or
   neither, is rejected with 400 (`Supply exactly one of newUnitCost or costDelta`).

   What happens next depends on the size of the value delta, and you do not choose it:

   - If the delta clears an approval threshold the record is created `PENDING_APPROVAL` and must be
     approved via `POST /v1/inventory/valuation/revaluations/{revaluationId}/approve`
     (`inventory:valuation:adjust`). Only then is the cost state restated.
   - Otherwise it is created `AUTO_APPLIED` and has already taken effect. **Do not call approve on
     it** — approving anything not in `PENDING_APPROVAL` fails.

   Read `status` on the create response rather than assuming. **There is no bulk or category-scoped
   revaluation, and this is by design** (ADR-0048 IMP-004): restating inventory value is per-SKU and
   approval-gated. Do not script around it.

7. **Flip the flag.** Set `POS_INVENTORY_SKU_CATEGORY_RESOLVE_FROM_REPLICA=true` and restart the
   service. On boot `SkuCategoryCutoverStartupCheck` logs an INFO line naming how many SKUs now
   resolve their costing method from a `SKU_CATEGORY` row and across how many configuration rows —
   that line is the flip's own audit record, so capture it. It logs a WARN first only when the
   impact scan hit `pos.inventory.sku-category.impact-sku-cap`, in which case that count is a lower
   bound; raise the cap and re-run step 2 before trusting it. The check never fails startup, so a
   missing line means the check itself errored — look for the "did not complete" WARN.

8. **Verify.** Re-run step 2 with the flag on, and read the right field.

   `impactedSkuCount` will be `0`. Be clear about why: with the flag on, a matched SKU resolves from
   its category, so its current method *is* its projected method and nothing is pending by
   construction. That zero confirms the report agrees the flip took effect; it is **not** an
   independent audit of the flip, and it cannot go non-zero to warn you.

   The fields that actually carry information after the flip are:

   - `categoryMatchedSkuCount` — the SKUs the category step now governs. Compare it against the
     number you read in step 2 *before* the flip; they should match. Materially larger means a
     category override is matching more products than you signed off on, most often because more
     products were replicated in between.
   - `truncated` — must be `false`. If `true`, every row-derived count is a lower bound and this
     verification is inconclusive until you raise `POS_INVENTORY_SKU_CATEGORY_IMPACT_SKU_CAP` and
     re-run.
   - The startup WARN/INFO line from step 7, which reports the same governed count independently.

   For the SKUs you revalued in step 6, verify the value moved as intended by reading the J4
   revaluation records, not this report — restated opening values are outside what it measures.

9. **Rollback.** Set the variable back to `false` and restart. Resolution returns to
   `NoOpSkuCategoryProvider` immediately and the `SKU_CATEGORY` step goes inert again. Note what
   rollback does **not** undo: revaluations posted in step 6 are separate approved J4 records and
   stay posted. Reversing one is another revaluation, not a rollback.

### Reconciliation manifests and drift detection

Owners publish a per-window summary (count + checksum of the window's eventIds) on
`{domain}.manifest.v1`; consumers recompute it from their processing log and, on mismatch,
publish a `{domain}.outbox.replay-requested` command themselves — repair is automatic and needs
no operator action. Everything flows over the event channel; there are no synchronous
domain-to-domain reconciliation calls (ADR-0044 §4). Reference pair: `pos-workorder`
`ManifestPublisher` → `pos-customer` `WorkorderManifestListener`.

Operational signals:

- `replica_drift_total{owner,entity}` (consumer side) — one increment per mismatched window.
  Occasional single increments self-heal via replay; a **steadily increasing** counter means the
  repair loop is not converging (owner's command listener down, replay permission/topic issue, or
  a poison message that can never be recorded) — check the consumer's warn log for the window
  details (expected vs observed count/checksum) and the owner's `workorder.commands.v1` consumer.
- `workorder.manifest.published` / `workorder.manifest.publish.failures` (owner side) — manifests
  stopping entirely means the owner's scheduler or broker connection is down; consumers see no
  drift while blind, so alert on manifest absence too.
- Tuning: `workorder.manifest.window` (default `PT1H`), `workorder.manifest.grace` (default
  `PT5M` — how long after a window closes before its manifest publishes; raise it if consumer lag
  causes false-positive drift), `workorder.manifest.poll-interval-ms`.

Manual drift drill (compose stack, both `WORKORDER_KAFKA_ENABLED=true` and
`pos.customer.kafka.enabled=true`):

1. Create/update a workorder so an event lands in `event_outbox` and the customer replica.
2. Corrupt the consumer: `DELETE FROM processing_log WHERE event_id = '<eventId>'` (and the
   projected row) in the customer schema.
3. Wait one manifest cycle (or temporarily set `workorder.manifest.window=PT2M`,
   `workorder.manifest.grace=PT30S`). The customer logs `Replica drift detected`, increments
   `replica_drift_total`, and the owner re-emits; the event is reprocessed and the projection
   restored within the following poll.
4. Verify `replica_drift_total` stops increasing on subsequent windows.

Until producers emit the full `DomainEventEnvelope` (with `aggregateVersion`), manifests detect
**lost/undelivered events**, not corrupted-in-place replica rows; per-aggregate state comparison
arrives with the envelope migration (Phase 1/2).

## Labor-Guide Imports (pos-catalog, #1569)

Estimated service times enter `service_labor_standard` through operator-triggered feed imports
from STORE-licensed sources — never a lazy read-through refresh, so the quote path never pays
vendor latency. Full design: `pos-catalog/docs/service-time-sourcing-plan.md`.

- **Trigger an import** (`catalog:labor_standard:import`):
  `POST /v1/catalog/labor-guide-imports?sourceCode=MOCKGUIDE`. Re-running a revision resumes
  from the first missing chunk; an already-COMPLETE revision is a recorded no-op.
- **Check completeness**: `GET /v1/catalog/labor-guide-imports/incomplete` lists revisions whose
  chunks or line counts have not reconciled (status `APPLYING` or `INCOMPLETE`); re-run the
  import to resume. COMPLETE is a counted fact — every expected chunk applied and the line
  count matched the vendor's manifest.
- **Curate unmapped operations**: `GET /v1/catalog/labor-guide-imports/unmapped` lists vendor
  codes no `service_operation_xref` row maps (entries suffixed `#<TYPE>` carry a time class the
  platform does not model). Add the xref row (or decide the code is not wanted), then re-run
  the import — mapping is deliberate curation, never automatic.
- **Source precedence** is data: `labor_time_source_policy` rows order sources per time type
  (lower `precedence` wins) and are seeded by `R__seed_reference_catalog_6_labor_guide.sql`;
  edit rows rather than code to re-rank sources.
- **Degradation**: if the resolve edge is down, pos-workorder prefills from its
  `ext_catalog_service` replica's `default_labor_hours` (fed by `catalog.service.updated`
  schema v2) and, failing that, the service writer types the hours — estimating never blocks
  on a guide.

## Related Documentation

- **Platform-level runbook**: `durion/docs/OPERATIONS_RUNBOOK.md`
- **Architecture guide**: [ARCHITECTURE_GUIDE.md](ARCHITECTURE_GUIDE.md)
- **Development guide**: [DEVELOPMENT_GUIDE.md](DEVELOPMENT_GUIDE.md)
