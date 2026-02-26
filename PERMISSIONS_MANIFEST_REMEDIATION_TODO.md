# Permissions Manifest Remediation TODO

## Objective
Migrate permission registration to ADR-0025 standard:
- canonical source: `src/main/resources/permissions.yaml`
- startup registration: `PermissionRegistrationSupport`
- no inline `PermissionDefinition.of(...)` registration lists

## Completed in this batch
- [x] Add shared manifest loader in `pos-security-common`
- [x] Extend `PermissionRegistrationSupport` with manifest-backed constructor
- [x] Migrate modules to manifest-backed registration:
  - [x] `pos-accounting`
  - [x] `pos-catalog`
  - [x] `pos-customer`
  - [x] `pos-order`
  - [x] `pos-people`
  - [x] `pos-price`
  - [x] `pos-shop-manager`
  - [x] `pos-workorder`
  - [x] `pos-inventory` (replaced custom initializer flow)
- [x] Add `permissions.yaml` to each migrated module
- [x] Compile verification across impacted modules
- [x] Migrate additional modules with authority-based checks:
  - [x] `pos-documents`
  - [x] `pos-invoice`
  - [x] `pos-location`

## Remaining remediation backlog

### Phase A: adopt manifest-backed registration where authorization exists
- [ ] `pos-mcp-server` (currently role-based security; migrate if permission authorities are introduced)
- [x] `pos-event-receiver` reviewed (no module-owned authority checks currently)
- [x] `pos-image` reviewed (no module-owned authority checks currently)
- [x] `pos-inquiry` reviewed (no module-owned authority checks currently)
- [x] `pos-tax` reviewed (no module-owned authority checks currently)
- [x] `pos-vehicle-fitment` reviewed (no module-owned authority checks currently)
- [x] `pos-vehicle-inventory` reviewed (no module-owned authority checks currently)

### Phase B: governance and hardening
- [ ] Add CI validation for permissions manifest schema
- [ ] Add CI check for duplicate permission names per module
- [ ] Add CI parity check (`permissions.yaml` vs authorities enforced in code)
- [ ] Add ArchUnit/static rule to block new inline permission registration lists
- [ ] Generate consolidated permission inventory artifact in CI

## Verification command used
```bash
./mvnw -pl pos-security-common,pos-accounting,pos-catalog,pos-customer,pos-order,pos-people,pos-price,pos-shop-manager,pos-workorder,pos-inventory,pos-documents,pos-invoice,pos-location -am -DskipTests compile
```
