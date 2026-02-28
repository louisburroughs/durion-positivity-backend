# ADR Non-Compliance Report

## Scan Metadata
- timestamp: 2026-02-28T21:41:35Z
- target_repo: durion-positivity-backend
- adr_root: /home/louisb/Projects/durion/docs/adr
- output_format: md
- module_scope: [pos-shop-manager]
- severity_threshold: low

## ADR Rules Audited
| ADR | Rule ID | Check Type | Description |
|-----|---------|------------|-------------|
| ADR-0024 | mutable_entity_must_have_created_updated_timestamps | machine-checkable | New mutable JPA entities must include both `createdAt` and `updatedAt`; inserts set both, updates change `updatedAt` only. |
| ADR-0024 | mutable_entity_must_use_jpa_auditing | machine-checkable | Mutable entities must use `@EntityListeners(AuditingEntityListener.class)`, `@CreatedDate`, and `@LastModifiedDate`. |
| ADR-0013 | touched_entities_should_use_uuidv7_id_annotation | manual-review-required | Touched/new entities should migrate from ad-hoc `@PrePersist` ID generation to shared `@UUIDv7Id`/`@IdGeneratorType` standard. |
| ADR-0026 | service_package_contract_only_interfaces | manual-review-required | `com.positivity.{domain}.service..` is contract-only and should contain interfaces; implementation/detail classes should remain internal. |

## Findings
### High

- ID: NC-0001
  - severity: high
  - confidence: high
  - adr_id: ADR-0024
  - rule_id: mutable_entity_must_have_created_updated_timestamps
  - module: pos-shop-manager
  - class_name: com.positivity.shopmanager.internal.entity.TravelBlock
  - file: pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/entity/TravelBlock.java
  - line: 22
  - evidence: Entity declares `@Entity`, `@Id`, `@PrePersist` (`:16`, `:23`, `:39`) but contains no `createdAt`/`updatedAt` fields and no `@CreatedDate`/`@LastModifiedDate` annotations.
  - non_compliance: New mutable entity lacks mandatory lifecycle timestamp fields and auditing annotations.
  - repair_recommendation: Add `createdAt` and `updatedAt` columns/fields; annotate with `@CreatedDate` and `@LastModifiedDate`; add `@EntityListeners(AuditingEntityListener.class)`; add migration SQL for `travel_block` including both timestamp columns.
  - repair_effort: M
  - repair_owner: backend-module-owner

- ID: NC-0002
  - severity: high
  - confidence: high
  - adr_id: ADR-0024
  - rule_id: mutable_entity_must_have_created_updated_timestamps
  - module: pos-shop-manager
  - class_name: com.positivity.shopmanager.internal.entity.OverrideRecord
  - file: pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/entity/OverrideRecord.java
  - line: 23
  - evidence: Entity declares `@Entity`, `@Id`, `@PrePersist` (`:17`, `:25`, `:44`) but no `createdAt`/`updatedAt` and no Spring Data auditing annotations.
  - non_compliance: New mutable entity lacks mandatory lifecycle timestamp fields and auditing annotations.
  - repair_recommendation: Either (a) make model explicitly immutable via `@Immutable` and document ADR-0024 exemption, or (b) implement full `createdAt`/`updatedAt` auditing pattern with migration.
  - repair_effort: M
  - repair_owner: backend-module-owner

- ID: NC-0003
  - severity: high
  - confidence: high
  - adr_id: ADR-0024
  - rule_id: mutable_entity_must_have_created_updated_timestamps
  - module: pos-shop-manager
  - class_name: com.positivity.shopmanager.internal.entity.Assignment
  - file: pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/entity/Assignment.java
  - line: 25
  - evidence: Entity uses `@Entity`, `@Id`, and `@PrePersist` (`:19`, `:27`, `:61`) with no `createdAt`/`updatedAt` fields and no auditing annotations.
  - non_compliance: New mutable CAP-138 entity does not follow ADR-0024 timestamp policy.
  - repair_recommendation: Add auditing fields + annotations and corresponding Flyway migration updates for assignment persistence.
  - repair_effort: M
  - repair_owner: backend-module-owner

- ID: NC-0004
  - severity: high
  - confidence: high
  - adr_id: ADR-0024
  - rule_id: mutable_entity_must_have_created_updated_timestamps
  - module: pos-shop-manager
  - class_name: com.positivity.shopmanager.internal.entity.AssignmentMechanic
  - file: pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/entity/AssignmentMechanic.java
  - line: 24
  - evidence: Entity has `@Entity`, `@Id`, `@PrePersist` (`:18`, `:26`, `:40`) and no lifecycle timestamp/auditing annotations.
  - non_compliance: New mutable entity missing mandatory `createdAt`/`updatedAt` plus auditing listeners.
  - repair_recommendation: Add ADR-0024 audit fields and Spring auditing annotations; update schema migration for assignment-mechanic records.
  - repair_effort: M
  - repair_owner: backend-module-owner

- ID: NC-0005
  - severity: high
  - confidence: high
  - adr_id: ADR-0024
  - rule_id: mutable_entity_must_have_created_updated_timestamps
  - module: pos-shop-manager
  - class_name: com.positivity.shopmanager.internal.entity.HrIntegrationLog
  - file: pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/entity/HrIntegrationLog.java
  - line: 22
  - evidence: Entity has `@Entity`, `@Id`, `@PrePersist` (`:16`, `:24`, `:49`) and no `@CreatedDate`/`@LastModifiedDate` fields.
  - non_compliance: New mutable entity does not implement required audit timestamp policy.
  - repair_recommendation: Add `createdAt`/`updatedAt` auditing fields and listener annotations; add migration changes.
  - repair_effort: M
  - repair_owner: backend-module-owner

- ID: NC-0006
  - severity: high
  - confidence: high
  - adr_id: ADR-0024
  - rule_id: mutable_entity_must_have_created_updated_timestamps
  - module: pos-shop-manager
  - class_name: com.positivity.shopmanager.internal.entity.Mechanic
  - file: pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/entity/Mechanic.java
  - line: 26
  - evidence: Entity has `@Entity`, `@Id`, `@PrePersist` (`:20`, `:28`, `:57`) but no lifecycle auditing fields.
  - non_compliance: New mutable entity missing mandatory `createdAt`/`updatedAt` and auditing annotations.
  - repair_recommendation: Implement ADR-0024 auditing pattern and migration for mechanic table.
  - repair_effort: M
  - repair_owner: backend-module-owner

- ID: NC-0007
  - severity: high
  - confidence: high
  - adr_id: ADR-0024
  - rule_id: mutable_entity_must_have_created_updated_timestamps
  - module: pos-shop-manager
  - class_name: com.positivity.shopmanager.internal.entity.MechanicAuditLog
  - file: pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/entity/MechanicAuditLog.java
  - line: 22
  - evidence: Entity has `@Entity`, `@Id`, `@PrePersist` (`:16`, `:24`, `:49`) without `createdAt`/`updatedAt` auditing fields.
  - non_compliance: New mutable entity does not comply with ADR-0024 timestamp policy.
  - repair_recommendation: If intended immutable, mark `@Immutable` and document exemption; otherwise add full auditing fields and annotations.
  - repair_effort: M
  - repair_owner: backend-module-owner

- ID: NC-0008
  - severity: high
  - confidence: high
  - adr_id: ADR-0024
  - rule_id: mutable_entity_must_have_created_updated_timestamps
  - module: pos-shop-manager
  - class_name: com.positivity.shopmanager.internal.entity.MechanicSkill
  - file: pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/entity/MechanicSkill.java
  - line: 22
  - evidence: Entity has `@Entity`, `@Id`, `@PrePersist` (`:16`, `:24`, `:43`) and lacks both timestamp fields and auditing annotations.
  - non_compliance: New mutable entity not aligned with mandatory audit timestamp policy.
  - repair_recommendation: Add required lifecycle timestamp fields + auditing annotations and migration updates.
  - repair_effort: M
  - repair_owner: backend-module-owner

### Medium

- ID: NC-0009
  - severity: medium
  - confidence: medium
  - adr_id: ADR-0013
  - rule_id: touched_entities_should_use_uuidv7_id_annotation
  - module: pos-shop-manager
  - class_name: com.positivity.shopmanager.internal.entity.TravelBlock
  - file: pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/entity/TravelBlock.java
  - line: 39
  - evidence: Entity generates UUID via `@PrePersist` and `UUIDv7Generator.generate()` but has no shared `@UUIDv7Id` annotation.
  - non_compliance: New/touched entity has not migrated to shared Hibernate `@IdGeneratorType` UUIDv7 annotation standard.
  - repair_recommendation: Add shared `@UUIDv7Id` on ID field and remove per-entity ad-hoc ID generation callback when feasible.
  - repair_effort: S
  - repair_owner: backend-module-owner

- ID: NC-0010
  - severity: medium
  - confidence: medium
  - adr_id: ADR-0013
  - rule_id: touched_entities_should_use_uuidv7_id_annotation
  - module: pos-shop-manager
  - class_name: com.positivity.shopmanager.internal.entity.OverrideRecord
  - file: pos-shop-manager/src/main/java/com/positivity/shopmanager/internal/entity/OverrideRecord.java
  - line: 44
  - evidence: Uses `@PrePersist` for UUID assignment and does not apply `@UUIDv7Id`.
  - non_compliance: Touched/new CAP-138 entity not migrated to shared UUIDv7 ID annotation pattern.
  - repair_recommendation: Adopt shared `@UUIDv7Id` annotation and remove callback-based ID generation.
  - repair_effort: S
  - repair_owner: backend-module-owner

- ID: NC-0011
  - severity: medium
  - confidence: low
  - adr_id: ADR-0026
  - rule_id: service_package_contract_only_interfaces
  - module: pos-shop-manager
  - class_name: com.positivity.shopmanager.service.dto.ConflictOverrideRequest
  - file: pos-shop-manager/src/main/java/com/positivity/shopmanager/service/dto/ConflictOverrideRequest.java
  - line: 11
  - evidence: `service.dto` package contains concrete classes (for example `ConflictOverrideRequest`, `MechanicAvailabilityResult`, `AssignmentResponse`) even though ADR-0026 states `..service..` should be contract-only interfaces.
  - non_compliance: Potential violation of strict interface-only interpretation for `service..` package.
  - repair_recommendation: Clarify ADR-0026 interpretation for `service.dto`/`service.enums`. If strict, move DTO/enums under `internal.dto` + expose via service contracts; if exempt, document exemption in ADR or module standards.
  - repair_effort: M
  - repair_owner: architecture-owner

### Low
- None.

## Repair Queue
1. NC-0001 - Apply ADR-0024 auditing pattern to `TravelBlock` and add schema migration.
2. NC-0002 - Apply ADR-0024 pattern or explicit immutable exemption to `OverrideRecord`.
3. NC-0003 - Add auditing fields/annotations to `Assignment` and migration updates.
4. NC-0004 - Add auditing fields/annotations to `AssignmentMechanic` and migration updates.
5. NC-0005 - Add auditing fields/annotations to `HrIntegrationLog` and migration updates.
6. NC-0006 - Add auditing fields/annotations to `Mechanic` and migration updates.
7. NC-0007 - Add auditing fields/annotations or immutable exemption to `MechanicAuditLog`.
8. NC-0008 - Add auditing fields/annotations to `MechanicSkill` and migration updates.
9. NC-0009 - Migrate `TravelBlock` ID generation to `@UUIDv7Id` shared annotation.
10. NC-0010 - Migrate `OverrideRecord` ID generation to `@UUIDv7Id` shared annotation.
11. NC-0011 - Resolve ADR-0026 ambiguity for `service.dto`/`service.enums` and refactor or document exemption.

## Open Questions
- ADR-0026 says `com.positivity.{domain}.service..` should be interface-only, but current module patterns use `service.dto` and `service.enums`. Should these be explicitly exempted?
- For audit-log style entities introduced in CAP-138 (`OverrideRecord`, `MechanicAuditLog`), should the team adopt explicit `@Immutable` exemptions or standardize on full `createdAt`/`updatedAt` auditing fields?
