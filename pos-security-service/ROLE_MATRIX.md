## CAP-141 Candidate Role Matrix v0

| Role Name | Persona | Core Permissions | Endpoint Coverage | Audit Scope |
|---|---|---|---|---|
| READ_ONLY_SCHEDULER | Scheduler/Viewer | shop:schedule:read, shop:assignment:read | GET /v1/shop/*, GET /v1/shop/audit | none (read-only) |
| DISPATCHER | Dispatcher | shop:schedule:write, shop:workorder:dispatch | POST/PUT /v1/shop/schedules/*, POST /v1/shop/dispatch | dispatch events |
| SHOP_MANAGER | Shop Manager | shop:*, shop:audit:read | All /v1/shop/* | all shop mutations |
| SECURITY_ADMIN | Security Administrator | security:role:*, security:permission:* | All /v1/roles/*, /v1/users/*/roles | all RBAC mutations |

Discrepancies: None. All permissions are registered in `.github/permissions/permissions_v2.yml`.
