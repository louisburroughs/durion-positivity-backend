## CAP-141 Candidate Role Matrix v1 (permissions_v2 aligned)

| Role Name | Persona | Core Permissions (Canonical from `permissions_v2.yml`) | Endpoint Coverage | Audit Scope |
| --- | --- | --- | --- | --- |
| READ_ONLY_SCHEDULER | Scheduler/Viewer | shop:schedule:view, appointments:view, shop:location:view, shop:bay:view | Read endpoints guarded by schedule/appointment/location/bay view permissions | none (read-only) |
| DISPATCHER | Dispatcher | shop:schedule:edit, appointments:create, appointments:reschedule, appointments:cancel, shop:bay:assign | Appointment create/reschedule/cancel and schedule-edit flows | dispatch and schedule-change events |
| SHOP_MANAGER | Shop Manager | shop:location:view, shop:location:create, shop:location:edit, shop:location:deactivate, shop:bay:view, shop:bay:create, shop:bay:edit, shop:bay:assign, shop:schedule:view, shop:schedule:edit, appointments:view, appointments:create, appointments:reschedule, appointments:cancel | Full shop-manager capabilities currently represented in canonical registry | all shop mutation events |
| SECURITY_ADMIN | Security Administrator | security:role:view, security:role:create, security:role:edit, security:role:delete, security:role:assign, security:permission:view, security:permission:register, security:user:view, security:user:create, security:user:edit, security:user:delete | RBAC/identity management endpoints in `pos-security-service` | all RBAC mutations and user-admin events |

### Known Discrepancies vs Canonical Registry

- `pos-shop-manager` controllers still reference non-canonical authorities not present in `permissions_v2.yml`:
  - `shopmgmt.audit.view`, `shopmgmt.audit.admin`
  - `workexec.assignment.create`, `workexec.assignment.read`
  - `shopmgmt.assignment.view`
  - `shopmgr.appointment.override`
- **RESOLVED**
- `pos-security-service` `PermissionController` references plural authorities not present in `permissions_v2.yml`:
  - `security:permissions:view`
  - `security:permissions:register`
  - Canonical names are singular: `security:permission:view` and `security:permission:register`.
- **END RESOLVED**
- Several security endpoints are currently `hasRole('ADMIN'|'MANAGER')` based rather than canonical permission-based. Role-to-permission mapping must remain explicit to avoid drift.
