Scan JPA entities vs Flyway migrations

## JPA Entities Without Flyway Migrations

### True Orphans — No `CREATE TABLE` in Flyway

| Module | Entity | Table Name | Severity |
|--------|--------|-----------|----------|
| **pos-location** | `Location` | `location` | **HIGH** — ALTER migrations reference it |
| **pos-location** | `LocationParent` | `location_parent` | HIGH |
| **pos-people** | `Person` | `person` | HIGH — core entity |
| **pos-shop-manager** | `Shop` | `shop` | **HIGH** — ALTER migrations reference it |
| **pos-shop-manager** | `Bay` | `bay` | MEDIUM |
| **pos-shop-manager** | `Certification` | `certification` | MEDIUM |
| **pos-shop-manager** | `MobileUnit` | `mobile_unit` | MEDIUM |
| **pos-shop-manager** | `ShopQualification` | `shop_qualification` | MEDIUM |
| **pos-shop-manager** | `ShopServiceEntry` | `shop_service` | MEDIUM |
| **pos-shop-manager** | `Technician` | `technician` | MEDIUM |
| **pos-workorder** | `ApprovalConfiguration` | `approval_configuration` | MEDIUM |
| **pos-workorder** | `ChangeRequest` | `change_request` | MEDIUM |
| **pos-workorder** | `Customer` | `customer` | MEDIUM — local to pos-workorder |
| **pos-workorder** | `EstimateSequence` | `estimate_sequence` | MEDIUM |
| **pos-workorder** | `Vehicle` | `vehicle` | MEDIUM — local to pos-workorder |
| **pos-workorder** | `WorkorderServiceLine` | `workorder_service` | MEDIUM |

### Naming Mismatches — Entity Default ≠ Flyway Table

| Module | Entity | Spring Default Name | Flyway Table |
|--------|--------|-------------------|-------------|
| **pos-catalog** | `CompetitorXReference` | `competitor_x_reference` | `competitorxreference` |
| **pos-catalog** | `OEMXReference` | `o_e_m_x_reference` | `oemxreference` |

### Expected Gaps (No Action Needed)

| Module | Entity | Reason |
|--------|--------|--------|
| pos-customer | `AbstractParty` | Abstract `TABLE_PER_CLASS` — children have tables |
| pos-vehicle-inventory | `Car`, `CommercialTruck`, `PassengerTruck`, `Van` | `SINGLE_TABLE` children in `vehicle_entity` |
| pos-event-receiver | `EmittedEventHourly` | `@Immutable` view entity |

**Summary:** 16 entities across pos-location, pos-people, pos-shop-manager, and pos-workorder have no Flyway `CREATE TABLE` and rely on `ddl-auto: create-drop`. They will fail with `ddl-auto: validate` in production. The `location` and `shop` tables are highest priority since existing ALTER migrations already reference them.
