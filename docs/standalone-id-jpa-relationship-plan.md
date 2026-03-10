# Standalone Id to JPA Relationship Migration Plan (Core Entities Only)

## Scope Decision

This migration excludes all audit and event entities for now.

- In scope: entities listed under Core Entries in [docs/entity-fk-candidates.md](docs/entity-fk-candidates.md)
- Out of scope: all entities listed under Audit and Event Entries in [docs/entity-fk-candidates.md](docs/entity-fk-candidates.md)

## Goals

- Replace standalone UUID/String foreign-key style fields with explicit JPA relationships where domain ownership is local to the same service/module.
- Preserve service boundaries across microservices by avoiding direct JPA links to entities owned by other modules.
- Update repository/service/controller and tests in the same change set per batch.

## Guardrails

- Do not create cross-service JPA relationships.
- Keep existing column names stable when possible using @JoinColumn(name = "...").
- Prefer ManyToOne for most FK-like references; use OneToOne only when uniqueness is enforced by data model.
- For legacy read/write compatibility, migrate in two steps when needed:
  - Step A: add relationship mapped to existing FK column while preserving old scalar field as read-only/derived if required.
  - Step B: remove deprecated scalar field after tests and consumers are updated.

## Delivery Batches

Use small module-scoped batches to reduce risk and keep PRs reviewable.

### Batch 0: Preparation

- [x] Freeze the candidate list snapshot from Core Entries in [docs/entity-fk-candidates.md](docs/entity-fk-candidates.md).
- [x] Tag each candidate field as:
  - same-module entity candidate (convert now)
  - external-service reference (keep scalar Id)
  - ambiguous (requires domain decision)
- [x] Define naming convention for relationship fields (for example: customer instead of customerId).
- [ ] Add migration checklist template for each entity conversion.

### Batch 1: Low-Risk Local Aggregates

- [x] Convert child-to-parent relationships where parent entity already exists in same module and table has stable FK column.
- [ ] Add repository query updates to navigate relationships.
- [ ] Update DTO mapping logic to avoid lazy-loading pitfalls.
- [ ] Add/adjust integration tests for persistence and retrieval.

### Execution Log

- [x] 2026-03-09: Generated core-scope candidate inventory and separated audit/event entities.
- [x] 2026-03-09: Implemented first low-risk conversion in `pos-catalog` by removing standalone `supplierItemCostId` from `CostTierEntity` and keeping `supplierItemCost` JPA relationship as source of truth.
- [x] 2026-03-09: Converted dual-mapped `priceBookId` in `PriceBookRuleEntity`; updated `PriceBookRuleRepository` JPQL to `r.priceBook.priceBookId`; updated `PriceBookServiceImpl` to use `entity.getPriceBook().getPriceBookId()`.
- [x] 2026-03-09: Validation completed for this step with `./mvnw -pl pos-catalog -DskipTests compile` and `./mvnw -pl pos-catalog -Dtest=PriceBookContractBehaviorIT test`.
- [x] 2026-03-09: Converted `InvoiceItem.invoiceId` in `pos-invoice` by removing scalar field and using `invoice` relationship only; updated `InvoiceItemRepository` method to `findByInvoice_Id(...)`.
- [x] 2026-03-09: Converted `Receipt.invoiceId` in `pos-invoice` to `@ManyToOne Invoice`; updated `ReceiptRepository` to `countByInvoice_Id(...)` and `ReceiptServiceImpl` mapping/creation logic to use relationship.
- [x] 2026-03-09: Validation completed for receipt conversion with `./mvnw -pl pos-invoice -DskipTests compile` and `./mvnw -pl pos-invoice -Dtest=ReceiptServiceImplTest test`.
- [x] 2026-03-09: Converted `PaymentIntent.invoiceId` in `pos-invoice` to `@ManyToOne Invoice`; updated `PaymentServiceImpl` and `PaymentReversalServiceImpl` invoice ownership checks and payment intent creation logic to use relationship path.
- [x] 2026-03-09: Updated payment unit tests for relationship-backed fixtures (`PaymentServiceImplTest`, `PaymentReversalServiceImplTest`) and removed obsolete scalar setter usage.
- [x] 2026-03-09: Validation completed for payment conversion with `./mvnw -pl pos-invoice -Dtest=PaymentServiceImplTest,PaymentReversalServiceImplTest test`.
- [x] 2026-03-09: Converted `RefundRecord.paymentIntentId` and `RefundRecord.invoiceId` in `pos-invoice` to `@ManyToOne` relationships; updated `RefundRecordRepository` query method and `PaymentReversalServiceImpl` mapping/response logic.
- [x] 2026-03-09: Validation completed for refund-record conversion with `./mvnw -pl pos-invoice -DskipTests compile` and `./mvnw -pl pos-invoice -Dtest=PaymentReversalServiceImplTest test`.
- [x] 2026-03-09: Converted `Receipt.paymentIntentId` in `pos-invoice` to `@ManyToOne PaymentIntent`; updated `ReceiptServiceImpl` to load/validate payment intent ownership and map service DTO from relationship.
- [x] 2026-03-09: Updated `ReceiptServiceImplTest` fixtures and mocks for relationship-backed payment intent setup.
- [x] 2026-03-09: Validation completed for receipt payment-intent conversion with `./mvnw -pl pos-invoice -DskipTests compile` and `./mvnw -pl pos-invoice -Dtest=ReceiptServiceImplTest test`.
- [x] 2026-03-09: Converted `CountEntry.cycleCountTaskId` in `pos-inventory` to `@ManyToOne CycleCountTask`; updated `CountEntryRepository` methods (`findByCycleCountTask_TaskId...`, `countByCycleCountTask_TaskId`, latest-entry query derivation) and `CycleCountServiceImpl` mapping/creation logic.
- [x] 2026-03-09: Updated cycle-count tests for relationship-backed fixtures (`CycleCountServiceImplTest`, `CycleCountContractBehaviorIT`).
- [x] 2026-03-09: Validation completed for cycle-count conversion with `./mvnw -pl pos-inventory -DskipTests compile` and `./mvnw -pl pos-inventory -Dtest=CycleCountServiceImplTest,CycleCountContractBehaviorIT test`.
- [x] 2026-03-09: Bulk inventory pass: converted `InventoryVariance.sessionId` and `InventoryVariance.lineId` to `@ManyToOne` relationships (`ReceivingSession`, `ReceivingLine`); updated `ReceivingServiceImpl` variance persistence and response mapping.
- [x] 2026-03-09: Bulk inventory pass: converted `PutawayTask.sourceReceiptId` to `@ManyToOne GoodsReceiptEntity`; updated `PutawayTaskRepository` to `findBySourceReceipt_ReceiptId(...)` and updated `PutawayGenerationServiceImpl` to load/validate source receipt and map response from relationship.
- [x] 2026-03-09: Validation completed for bulk pass with `./mvnw -pl pos-inventory -DskipTests compile` and `./mvnw -pl pos-inventory -Dtest=PutawayGenerationServiceImplTest,ReceivingServiceImplTest test`.
- [x] 2026-03-09: Inventory ASN/receipt lineage pass: converted `AdvanceShippingNoticeEntity.poId`, `AsnLineEntity.poId`, `AsnLineEntity.poLineId`, `GoodsReceiptEntity.poId`, `GoodsReceiptEntity.asnId`, and `GoodsReceiptLineEntity.poLineId` to `@ManyToOne` relationships; updated `AsnServiceImpl`, `GoodsReceiptRepository`, and `AsnServiceImplTest` to relation-backed logic.
- [x] 2026-03-09: Validation completed for ASN/receipt lineage pass with `./mvnw -pl pos-inventory -Dtest=AsnServiceImplTest,AsnContractBehaviorIT test` and full regression `./mvnw -pl pos-inventory test`.
- [x] 2026-03-09: Catalog MSRP/replacement pass: converted `ProductMsrpEntity.productId` and `ProductReplacementEntity.originalProductId` / `ProductReplacementEntity.replacementProductId` to `@ManyToOne ProductEntity` relationships; updated `ProductMsrpRepository`, `ProductReplacementRepository`, `ProductMsrpServiceImpl`, `ProductLifecycleServiceImpl`, and `ProductDetailServiceImpl` to relation-backed query and mapping paths.
- [x] 2026-03-09: Fixed catalog compile blocker by replacing stale `validateProductExists(...)` call with `requireProduct(...)` in `ProductMsrpServiceImpl` and aligned formatting.
- [x] 2026-03-09: Validation completed for catalog pass with clean focused contracts `./mvnw -pl pos-catalog clean test -Dtest=MsrpContractBehaviorIT,ProductLifecycleContractBehaviorIT` and module test run `./mvnw -pl pos-catalog test`.
- [x] 2026-03-09: pos-order override lineage pass: converted `PriceOverride.orderId` and `PriceOverride.orderLineId` to `@ManyToOne` relationships (`SalesOrder`, `SalesOrderLine`) and converted `ApprovalRecord.priceOverrideId` to `@ManyToOne PriceOverride`; updated `PriceOverrideRepository`, `ApprovalRecordRepository`, and `PriceOverrideServiceImpl` to relation-backed paths while preserving API DTO scalar IDs.
- [x] 2026-03-09: Updated `PriceOverrideServiceTest` fixtures for relationship-backed overrides and repository method changes.
- [x] 2026-03-09: Validation completed for pos-order pass with focused tests `./mvnw -pl pos-order clean test -Dtest=PriceOverrideServiceTest,ContractBehaviorIT` and full module run `./mvnw -pl pos-order test`.
- [x] 2026-03-09: pos-shop-manager assignment lineage pass: converted `Assignment.appointmentId` to `@ManyToOne Appointment` and `AssignmentMechanic.assignmentId` to `@ManyToOne Assignment`; updated `AssignmentRepository`, `AssignmentMechanicRepository`, and `AssignmentServiceImpl` to relation-backed query and mapping paths while preserving API DTO scalar IDs.
- [x] 2026-03-09: Updated assignment-focused tests for relationship-backed fixtures and repository derivation changes (`AssignmentServiceTest`, `AssignmentServiceImplStory10Test`).
- [x] 2026-03-09: Validation completed for pos-shop-manager pass with focused tests `./mvnw -pl pos-shop-manager -Dtest=AssignmentServiceTest,AssignmentServiceImplStory10Test,AssignmentControllerStory10Test test` and full module run `./mvnw -pl pos-shop-manager test`.
- [x] 2026-03-09: pos-shop-manager appointment request lineage pass: converted `AppointmentServiceRequest.appointmentId` to `@ManyToOne Appointment`; updated `AppointmentServiceRequestRepository` derivation and `AppointmentsServiceImpl` persistence/loading logic to relation-backed paths while preserving API DTO scalar IDs.
- [x] 2026-03-09: Updated appointment-focused tests for relation-backed service-request fixtures (`AppointmentsServiceImplTest`, `AppointmentsServiceImplStory12Test`, `AppointmentsServiceNewBehaviorsTest`).
- [x] 2026-03-09: Validation completed for appointment request pass with focused tests `./mvnw -pl pos-shop-manager -Dtest=AppointmentsServiceImplTest,AppointmentsServiceNewBehaviorsTest,AppointmentsServiceImplStory12Test test` and full module run `./mvnw -pl pos-shop-manager test`.
- [x] 2026-03-09: pos-workorder labor-entry lineage pass: removed duplicate standalone `WorkorderLaborEntry.workorderId` and retained `@ManyToOne Workorder` as source of truth; updated `WorkorderLaborEntryRepository`, `WorkorderLaborServiceImpl`, `WorkexecTimeTrackingServiceImpl`, and `WorkorderLaborEntryResponse` to relation-backed query and mapping paths while preserving API DTO scalar IDs.
- [x] 2026-03-09: Updated workorder labor/time contract tests for relationship-backed fixtures and repository method rename (`WorkorderLaborContractBehaviorIT`, `WorkorderDetailVisibilityContractBehaviorIT`, `WorkexecTimeTrackingContractBehaviorIT`, `WorkexecJobTimeTotalsContractBehaviorIT`, `WorkexecLaborPerformedContractBehaviorIT`).
- [x] 2026-03-09: Validation completed for pos-workorder pass with focused tests `./mvnw -pl pos-workorder -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=WorkorderLaborContractBehaviorIT,WorkexecTimeTrackingContractBehaviorIT,WorkexecJobTimeTotalsContractBehaviorIT,WorkorderDetailVisibilityContractBehaviorIT,WorkexecLaborPerformedContractBehaviorIT test` and module regression verification via `pos-workorder/target/surefire-reports` (no non-zero failures/errors).
- [x] 2026-03-09: pos-workorder break-segment lineage pass: removed duplicate standalone `BreakSegment.workSessionId` and retained `@ManyToOne WorkSession` as source of truth; updated `BreakSegmentRepository` derivations and `WorkSessionServiceImpl` ownership/mapping logic to relation-backed paths while preserving API DTO scalar IDs.
- [x] 2026-03-09: pos-workorder change-request lineage pass: converted `ChangeRequest.workorderId` to `@ManyToOne Workorder`; updated `ChangeRequestRepository` derivations, `ChangeRequestServiceImpl`, `ChangeRequestResponse`, and `WorkorderStateMachine` to relation-backed query and mapping paths while preserving API DTO scalar IDs.
- [x] 2026-03-09: Updated work-session and change-request focused tests for relationship-backed fixtures and repository derivation changes (`WorkSessionServiceImplTest`, `WorkSessionContractBehaviorIT`, `WorkorderStateMachineTest`, `WorkorderStartContractBehaviorIT`, `WorkorderCompletionContractBehaviorIT`, `WorkorderCompletionTest`).
- [x] 2026-03-09: Validation completed for incremental pos-workorder passes with focused tests `./mvnw -pl pos-workorder -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=WorkSessionServiceImplTest,WorkSessionContractBehaviorIT,WorkorderStateMachineTest,WorkorderStartContractBehaviorIT,WorkorderCompletionContractBehaviorIT,WorkorderCompletionTest test` and full module run `./mvnw -pl pos-workorder -am -Dsurefire.failIfNoSpecifiedTests=false test`.
- [ ] Deferred: `CycleCountTask.latestCountEntryId` relationship conversion due cyclic persistence/teardown risk in current contract setup; revisit as dedicated follow-up with explicit lifecycle strategy.
- [ ] Next: Move to the next module-scoped same-module FK candidate batch after pos-workorder validation (inventory deferred item remains unchanged).

### Batch 2: Medium-Risk Shared Domain Objects

- [ ] Convert references used heavily by business logic and joins.
- [ ] Add fetch strategy tuning (lazy by default, targeted fetch joins in queries).
- [ ] Remove duplicated manual join logic in repositories/services.
- [ ] Add performance regression checks for query count and N+1 risk.

### Batch 3: Complex Relationship Graphs

- [ ] Convert entities with multiple Id candidates and potential circular links.
- [ ] Introduce helper methods for bidirectional association consistency where needed.
- [ ] Validate cascade/orphan settings explicitly (no implicit broad cascade).
- [ ] Expand transactional tests for create/update/delete flows.

### Batch 4: Cleanup

- [ ] Remove deprecated scalar Id fields that are fully replaced.
- [ ] Remove obsolete repository methods relying on scalar Id lookups.
- [ ] Update module docs and entity relationship diagrams.
- [ ] Re-run full module test suites and architecture checks.

## Test Update Plan (Required with Every Batch)

### Unit Tests

- [ ] Entity mapping tests for new relationships and nullability rules.
- [ ] Mapper tests (entity <-> DTO) preserving API contract.
- [ ] Service tests for behavior previously based on scalar Id checks.

### Repository Tests

- [ ] Query tests updated for relationship navigation paths.
- [ ] Fetch behavior tests for lazy/eager assumptions.
- [ ] Constraint tests for FK integrity and delete behavior.

### Integration Tests

- [ ] End-to-end create/update/read flows with relationship-backed persistence.
- [ ] Data setup cleanup order validation for FK-safe teardown.
- [ ] Regression tests for endpoints that accept Id values in request payloads.

### Non-Functional Tests

- [ ] Query count checks on key use cases to catch N+1 regressions.
- [ ] Migration compatibility test if temporary dual-mapping is used.

## Conversion Checklist Per Entity

- [ ] Confirm target entity is in same module (otherwise keep scalar Id).
- [ ] Add relationship field with @ManyToOne/@OneToOne and @JoinColumn using existing column name.
- [ ] Decide optional vs nullable and enforce with annotations + DB constraints.
- [ ] Update equals/hashCode/toString safety (avoid traversing lazy relationships).
- [ ] Update repositories, specifications, and query methods.
- [ ] Update DTOs/serializers to avoid recursive graph serialization.
- [ ] Update tests in same PR.
- [ ] Validate module build and tests before merge.

## Prioritization Strategy

- Start with modules having the highest concentration of clear same-module relationships from Core Entries.
- Defer ambiguous candidates until domain owner review.
- Keep each PR limited to one module (or one bounded context) plus tests.

## Open Questions

1. Which Core Entry modules should be first-wave (recommended: one low-risk module to prove pattern)?
2. For API contracts that currently expose scalar Ids, do we keep payload shape unchanged and map internally to relationships?
3. For cross-service references, should we formalize them as value-object reference types instead of raw UUID/String fields?

## Exit Criteria

- [ ] All approved same-module Core Entry standalone FK-style fields are represented as JPA relationships.
- [ ] No cross-service JPA links introduced.
- [ ] Tests updated and passing for each migrated module.
- [ ] Audit/Event entities remain untouched in this phase.
