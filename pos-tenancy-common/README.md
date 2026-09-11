# pos-tenancy-common

The ADR-0062 tenancy runtime every adopted module gets by auto-configuration: `TenantContext`,
the request/record/scheduler bindings, the connection and Hibernate resolvers, and the
classification annotations. Conventions, the binding table and the add-a-table checklist live in
`docs/TENANCY_SCHEMA.md`; this file covers what the library configures.

## Configuration (`pos.tenancy.*`)

| Property | Default | Purpose |
| --- | --- | --- |
| `default-tenant-id` | unset | Transitional single-tenant binding for every unbound path (ADR-0062 §9); unset means strict |
| `enforce` | `true` | Refuse unbound requests and records once no default applies |
| `tenants` | empty | Tenants `TenantIterator` visits in `STATIC` mode; the starting snapshot in `REMOTE` mode |
| `unenforced-paths` | empty | Request path prefixes `TenantContextFilter` never refuses for lack of a tenant |
| `datasource.enabled` | `true` | Wrap the module's `DataSource` so every checkout binds `app.current_tenant` |
| `registry.mode` | `STATIC` | `STATIC` (`StaticTenantRegistry`) or `REMOTE` (`RemoteTenantRegistry`, below) |
| `registry.url` | `http://tenant/internal/v1/tenants` | `pos-tenant`'s internal list endpoint; the default is the Eureka service id and needs a `@LoadBalanced` builder, otherwise set a DNS host (`http://pos-tenant:8080/...` in Compose) |
| `registry.secret` | blank | Sent as `X-Tenant-Registry-Secret`; `pos.tenant.registry.api-secret` on the server |
| `registry.refresh` | `PT60S` | Snapshot refreshed at most this often, lazily on read |
| `registry.connect-timeout` / `registry.read-timeout` | `PT2S` / `PT5S` | Fetch timeouts |

## Tenant registry (plan WS4-2, decided 2026-09-10)

`TenantIterator.forEachActiveTenant(...)` runs per-tenant scheduled work once per tenant the
module's `TenantRegistry` returns.

- **`STATIC`** (default): `pos.tenancy.tenants`, else the default tenant, else nothing (the
  iterator logs a WARN when it visits no tenant).
- **`REMOTE`**: `RemoteTenantRegistry` polls `pos-tenant`'s shared-secret
  `GET /internal/v1/tenants` (a JSON array of `{tenantId, slug, displayName, status}`) and keeps
  the `ACTIVE` ids as its snapshot. The snapshot starts as the static list, so work can run before
  `pos-tenant` has answered once; a read older than `registry.refresh` triggers a refresh under a
  lock (one thread fetches, the others keep reading the old snapshot, no background thread). A
  transport error, a non-2xx, an empty body or a list with no `ACTIVE` tenant is a failure: the
  last good snapshot stands, a WARN is logged on the transition to failing and an INFO on
  recovery, each with the consecutive-failure count. The registry is never empty because of a
  bad answer.
- A module that keeps its own registry (`pos-security-service`'s `ext_tenant`-backed
  `ExtTenantRegistry`) declares a `TenantRegistry` bean and the auto-configured one backs off in
  either mode.

The `REMOTE` client is built from the module's `@LoadBalanced RestClient.Builder` when it
declares one (so `http://tenant/...` resolves through Eureka), else its single or `@Primary`
builder, else a plain one; the configured timeouts are applied either way. A plain builder
resolves the host through DNS only, so with one the default service-id URL is refused at startup
and `registry.url` must name a resolvable host. Only a 2xx answer replaces the snapshot; a 3xx,
like a 4xx or 5xx, keeps the last good one. With Micrometer on the
classpath the registry exposes `tenancy.registry.tenants` (snapshot size) and
`tenancy.registry.last_success_epoch_seconds` (`0` until the first successful fetch; alert on its
age).

Switching a module:

```yaml
pos:
  tenancy:
    registry:
      mode: REMOTE
      secret: ${POS_TENANT_REGISTRY_API_SECRET}
```

No module runs `REMOTE` yet; the default keeps runtime behaviour unchanged until a module is
configured.

## Development

```bash
./mvnw -pl pos-tenancy-common -am test
```
