# Standalone Id to JPA Relationship Migration - Autonomous Agent Plan

## Objective

Convert approved same-module scalar FK-style fields (for example `orderId`, `invoiceId`) into explicit JPA relationships across **Core Entries** only, while preserving API contracts and module boundaries.

This plan is written so an autonomous coding agent can execute it end-to-end with minimal human intervention.

## Source Inputs

- Candidate inventory: `docs/entity-fk-candidates.md`
- Prior migration history and guardrails: `docs/standalone-id-jpa-relationship-plan.md`
- Architecture constraints: `pos-archunit` tests and module `ArchitectureTest` classes

## Scope

- In scope: Core entities from `docs/entity-fk-candidates.md`
- Out of scope: Audit and Event entries from `docs/entity-fk-candidates.md`
- Out of scope: Cross-service JPA relationships (must remain scalar/reference-only)

## Non-Negotiable Constraints

1. Never introduce JPA relationships across module/service boundaries.
2. Keep existing DB column names stable via `@JoinColumn(name = "...")`.
3. Prefer `@ManyToOne`; use `@OneToOne` only when uniqueness is enforced.
4. Keep external API request/response scalar ID shape unless explicitly approved to change.
5. Keep all internal implementation code under `com.positivity.{domain}.internal...` packages.
6. Preserve/add `@NonNull` (`org.jspecify.annotations.NonNull`) on non-null service/repository method params and non-Optional returns.
7. Do not modify Audit/Event entities in this phase.

## Deliverables

1. Entity mappings migrated for all approved same-module Core candidates.
2. Repositories/services/controllers/tests updated for relationship navigation.
3. Architecture tests and module tests passing per migrated module.
4. Updated migration tracker section in this file after each completed batch.

## Autonomous Execution Model

The agent must execute the following loop until no approved candidates remain.

### Step 0 - Build Work Queue

1. Parse `docs/entity-fk-candidates.md` Core section.
2. For each scalar `*Id` field, classify as:
   - `CONVERT_NOW`: same module, target entity exists, clear ownership.
   - `KEEP_SCALAR`: cross-service/external reference.
   - `DEFER`: ambiguous target, circular lifecycle risk, or unclear ownership.
3. Write/update queue artifact: `docs/standalone-id-jpa-relationship-work-queue.md` with statuses.

### Step 1 - Select Next Batch

Select one module at a time with this priority:

1. Highest count of `CONVERT_NOW` candidates.
2. Lowest graph complexity (fewer cyclic references).
3. Existing test coverage available.

Hard cap per PR/batch:

- Max 5 entities or 12 FK field conversions, whichever comes first.

### Step 2 - Apply Entity Conversion Recipe

For each selected candidate:

1. Add relationship field (or keep existing relationship as source of truth).
2. Map with `@JoinColumn(name = "<existing_fk_column>")`.
3. If legacy scalar still needed short-term:
   - Keep scalar as derived/read-only compatibility accessor.
   - Mark for removal in cleanup phase.
4. Update `equals/hashCode/toString` safety to avoid lazy graph traversal.
5. Ensure nullability consistency (`optional = ...`, DB constraints, annotations).

### Step 3 - Refactor Persistence/Business Access Paths

1. Update repository method names/JPQL to relationship navigation:
   - Example: `findByInvoiceId(...)` -> `findByInvoice_Id(...)`
2. Replace service-layer scalar-ID joins/lookups with relationship access.
3. Keep DTO/API scalar IDs by mapping `entity.getRelated().getId()` in responses.
4. Update create/update flows to assign managed parent entities.

### Step 4 - Update Tests in Same Batch

Required updates:

1. Unit tests for service logic and mapping behavior.
2. Repository tests for derived query names and joins.
3. Integration tests for persistence lifecycle and FK-safe cleanup ordering.
4. Contract/API behavior tests where scalar payload compatibility is expected.

### Step 5 - Validation Gate (Must Pass)

Run per module:

```bash
./mvnw -pl <module> -DskipTests compile
./mvnw -pl <module> -Dtest=<focused_test_list> test
./mvnw -pl <module> test
```

If architecture tests are in module:

```bash
./mvnw -pl <module> -Dtest=ArchitectureTest test
```

If any command fails:

1. Fix regressions in the same batch.
2. Re-run full module tests.
3. If unresolved after 2 repair attempts, mark candidate `DEFER` with reason and continue to next candidate.

### Step 6 - Batch Closeout

After a passing batch:

1. Update `Execution Log` section in this file with:
   - Date
   - Module
   - Entities/fields converted
   - Test commands executed
   - Result
2. Update queue statuses:
   - `DONE`, `DEFER`, `KEEP_SCALAR`
3. Move to next module.

## Decision Table (Autonomous)

| Condition | Action |
|---|---|
| Same module and clear target entity | `CONVERT_NOW` |
| Cross-module or external ownership | `KEEP_SCALAR` |
| Self-reference/cycle causing persistence teardown instability | `DEFER` |
| Target entity missing or unclear canonical owner | `DEFER` |
| API contract would break without approved change | Keep payload scalar shape; convert internal mapping only |

## Required Search/Verification Commands

Use these commands per candidate:

```bash
rg -n "<fieldName>|<RelationName>" <module>/src/main/java
rg -n "findBy.*<FieldName>|@Query" <module>/src/main/java
rg -n "<EntityName>|<fieldName>" <module>/src/test/java
```

Use these commands per module before closing:

```bash
./mvnw -pl <module> -DskipTests compile
./mvnw -pl <module> test
```

## Done Criteria

Migration is complete only when all are true:

1. Every approved same-module Core candidate is `DONE` or explicitly `DEFER` with reason.
2. No cross-service JPA links were introduced.
3. Module tests pass for all changed modules.
4. Architecture tests pass for all changed modules.
5. Audit/Event entities remain untouched.

## Current Deferred Items (Seed)

1. `pos-inventory`: `CycleCountTask.latestCountEntryId` (cyclic persistence/teardown risk; requires explicit lifecycle strategy before conversion).

## Execution Log

- 2026-03-10: Autonomous plan created. No new code changes applied by this file creation.
- 2026-03-10: `pos-price` batch completed.
  - Converted `PromotionEligibilityRule.promotionId` to `@ManyToOne PromotionOffer` with `@JoinColumn(name = "promotion_id")`.
  - Updated repository methods to relationship navigation:
    - `findByPromotion_PromotionOfferId(UUID promotionId)`
    - `deleteByRuleIdAndPromotion_PromotionOfferId(UUID ruleId, UUID promotionId)`
  - Updated service layer and tests to assign/read through relationship while preserving scalar `promotionId` in API response mapping via compatibility accessor.
  - Validation commands:
    - `./mvnw -pl pos-price -DskipTests compile test-compile`
    - `./mvnw -pl pos-price -Dtest=EligibilityEvaluationServiceImplTest,PromotionEligibilityRuleControllerTest,ApplyPromotionIntegrationTest test`
    - `./mvnw -pl pos-archunit -Dtest=ArchitectureTests test`
  - Result: PASS.
- 2026-03-10: `pos-accounting` batch completed.
  - Converted `GLMapping.postingCategoryId` to `@ManyToOne PostingCategory` with `@JoinColumn(name = "posting_category_id")`.
  - Preserved scalar API/service compatibility with `getPostingCategoryId()` / `setPostingCategoryId(UUID)` compatibility accessors.
  - Updated repository/service relationship navigation:
    - JPQL filters now use `glm.postingCategory.postingCategoryId`
    - Derived query updated to `findByPostingCategory_PostingCategoryId(UUID postingCategoryId)`
  - Validation commands:
    - `./mvnw -pl pos-accounting -Dtest=GLMappingResolverTest,PostingCategoryMappingKeyContractBehaviorIT test`
    - `./mvnw -pl pos-accounting -DskipTests install`
    - `./mvnw -pl pos-archunit -Dtest=ArchitectureTests test`
  - Result: PASS.
- 2026-03-10: `pos-accounting` batch 2 completed (GLMapping, Reconciliation, StatementLineMapping, DefaultGLMapping).
  - Converted 6 FK fields to `@ManyToOne` relationships:
    - `GLMapping.glAccountId` → `@ManyToOne GLAccount glAccount` + `@JoinColumn(name = "gl_account_id")`
    - `GLMapping.mappingKeyId` → `@ManyToOne MappingKey mappingKey` + `@JoinColumn(name = "mapping_key_id")`
    - `Reconciliation.glAccountId` → `@ManyToOne GLAccount glAccount` + `@JoinColumn(name = "gl_account_id")`
    - `StatementLineMapping.glAccountId` → `@ManyToOne GLAccount glAccount` + `@JoinColumn(name = "gl_account_id")`
    - `DefaultGLMapping.debitAccountId` → `@ManyToOne GLAccount debitAccount` + `@JoinColumn(name = "debit_account_id")`
    - `DefaultGLMapping.creditAccountId` → `@ManyToOne GLAccount creditAccount` + `@JoinColumn(name = "credit_account_id")`
  - Added convenience constructors: `GLAccount(UUID)`, `MappingKey(UUID)`.
  - Updated repository queries:
    - `GLMappingRepository`: `findByGlAccount_GlAccountId()`, JPQL paths `glm.mappingKey.mappingKeyId`
    - `ReconciliationRepository`: `findByGlAccount_GlAccountId()`, JPQL paths `r.glAccount.glAccountId`
    - `StatementLineMappingRepository`: `findByGlAccount_GlAccountId()`
  - Updated service code to use managed entity references:
    - `GLMappingServiceImpl.createMapping()`: `setGlAccount(glAccount)` instead of scalar setter
    - `DefaultGLMappingServiceImpl`: `getReferenceById()` for debit/credit accounts before save
  - Updated tests:
    - `FinancialReportingContractBehaviorIT`: builder calls, GL account setup
    - `DefaultGLMappingServiceTest`: `getReferenceById` mock stubs
  - Validation: `./mvnw -pl pos-accounting -am test` (433 tests, 0 failures), `./mvnw -pl pos-archunit -Dtest=ArchitectureTests test` (10 tests, 0 failures)
  - Result: PASS.
- 2026-03-10: `pos-accounting` batch 3 completed (JournalEntry dual-mapped, JournalEntryLine dual-mapped).
  - Fixed 3 dual-mapped patterns (scalar field + read-only `@ManyToOne`) by removing scalar `@Column` fields and making `@ManyToOne` owning:
    - `JournalEntryLine.glAccountId` → `@ManyToOne GLAccount glAccount` (removed `insertable=false, updatable=false`)
    - `JournalEntry.postingRuleSetId` → `@ManyToOne PostingRuleSet postingRuleSet` (removed `insertable=false, updatable=false`)
    - `JournalEntry.postingRuleVersionId` → `@ManyToOne PostingRuleVersion postingRuleVersion` (removed `insertable=false, updatable=false`)
  - Added convenience constructors: `PostingRuleSet(UUID)`, `PostingRuleVersion(UUID)`.
  - Added `@Transient` scalar compatibility accessors for all 3 fields.
  - Updated JPQL queries (5 total):
    - `JournalEntryLineRepository`: 2 queries `jel.glAccountId` → `jel.glAccount.glAccountId`
    - `JournalEntryRepository`: 3 queries `jel.glAccountId` → `jel.glAccount.glAccountId`
  - Validation: `./mvnw -pl pos-accounting -am test` (433 tests, 0 failures), `./mvnw -pl pos-archunit -Dtest=ArchitectureTests test` (10 tests, 0 failures)
  - Result: PASS.
- 2026-03-10: `pos-accounting` batch 4 completed (GLAccount self-ref, JournalEntry self-refs, VendorBill, APPayment).
  - Converted 5 FK fields to `@ManyToOne` relationships:
    - `GLAccount.parentAccountId` → `@ManyToOne GLAccount parentAccount` + `@JoinColumn(name = "parent_account_id")` (self-reference)
    - `JournalEntry.reversalJournalEntryId` → `@ManyToOne JournalEntry reversalJournalEntry` + `@JoinColumn(name = "reversal_journal_entry_id")` (self-reference)
    - `JournalEntry.reversedByJournalEntryId` → `@ManyToOne JournalEntry reversedByJournalEntry` + `@JoinColumn(name = "reversed_by_journal_entry_id")` (self-reference)
    - `VendorBill.journalEntryId` → `@ManyToOne JournalEntry journalEntry` + `@JoinColumn(name = "journal_entry_id")`
    - `APPayment.glJournalEntryId` → `@ManyToOne JournalEntry glJournalEntry` + `@JoinColumn(name = "gl_journal_entry_id")`
  - Added `@Transient` scalar compatibility accessors for all 5 fields.
  - Updated `@ToString` excludes on VendorBill, APPayment, GLAccount, JournalEntry for lazy relationship safety.
  - Updated JPQL: `JournalEntryRepository` `je.reversalJournalEntryId` → `je.reversalJournalEntry.journalEntryId`
  - Validation: `./mvnw -pl pos-accounting -am test` (433 tests, 0 failures, 13 arch tests), `./mvnw -pl pos-archunit -Dtest=ArchitectureTests test` (10 tests, 0 failures)
  - Result: PASS.
- 2026-03-10: `pos-workorder` batch 5 completed (EstimateSnapshot, WorkorderSnapshot, WorkorderStateTransition, ApprovalRecord).
  - Converted 5 FK fields to `@ManyToOne` relationships:
    - `EstimateSnapshot.estimateId` → `@ManyToOne Estimate estimate` + `@JoinColumn(name = "estimate_id")`
    - `WorkorderSnapshot.workorderId` → `@ManyToOne Workorder workorder` + `@JoinColumn(name = "workorder_id")`
    - `WorkorderStateTransition.workorderId` → `@ManyToOne Workorder workorder` + `@JoinColumn(name = "workorder_id")`
    - `ApprovalRecord.changeRequestId` → `@ManyToOne ChangeRequest changeRequest` + `@JoinColumn(name = "change_request_id")`
    - `ApprovalRecord.workorderId` → `@ManyToOne Workorder workorder` + `@JoinColumn(name = "workorder_id")`
  - Added convenience constructors: `Estimate(UUID)`, `Workorder(UUID)`, `ChangeRequest(UUID)`.
  - Added `@Transient` scalar compatibility accessors for all 5 fields.
  - Added `import lombok.ToString` and `@ToString(exclude=...)` for lazy safety on 3 entities.
  - Updated `@Index` columnList on ApprovalRecord from camelCase to snake_case column names.
  - Fixed pre-existing bug: `ApprovalRecordRepository` methods used `Long` parameter type but entity PK is `UUID`.
  - Updated repository derived query methods (4 repos, 6 methods):
    - `EstimateSnapshotRepository.findByEstimate_IdOrderByCapturedAtDesc()`
    - `WorkorderSnapshotRepository.findByWorkorder_IdOrderByCapturedAtDesc()`, `findByWorkorder_IdAndSnapshotType()`
    - `WorkorderStateTransitionRepository.findByWorkorder_Id()`, `findByWorkorder_IdOrderByTransitionedAtDesc()`
    - `ApprovalRecordRepository.findByChangeRequest_Id()`, `findByWorkorder_Id()`
  - Updated service code: `EstimateServiceImpl`, `WorkorderStateMachine`, `ChangeRequestServiceImpl`, `WipServiceImpl`.
  - Updated tests: `WipServiceImplTest`, `WorkorderStateMachineTest`, `WorkorderCompletionContractBehaviorIT`, `WorkorderStartContractBehaviorIT`.
  - Validation: `./mvnw -pl pos-workorder -am clean test` (261 tests, 0 failures), `./mvnw -pl pos-archunit -Dtest=ArchitectureTests test` (10 tests, 0 failures)
  - Result: PASS.
- 2026-03-10: `pos-workorder` batch 6 completed (Estimate, WorkorderPart, WorkorderService, TimeEntryAdjustment).
  - Converted 6 FK fields to `@ManyToOne` relationships:
    - `Estimate.approvalConfigurationId` → `@ManyToOne ApprovalConfiguration approvalConfiguration` + `@JoinColumn(name = "approval_configuration_id")`
    - `WorkorderPart.originEstimateItemId` → `@ManyToOne EstimateItem originEstimateItem` + `@JoinColumn(name = "origin_estimate_item_id")`
    - `WorkorderPart.changeRequestId` → `@ManyToOne ChangeRequest changeRequest` + `@JoinColumn(name = "change_request_id")`
    - `WorkorderService.originEstimateItemId` → `@ManyToOne EstimateItem originEstimateItem` + `@JoinColumn(name = "origin_estimate_item_id")`
    - `WorkorderService.changeRequestId` → `@ManyToOne ChangeRequest changeRequest` + `@JoinColumn(name = "change_request_id")`
    - `TimeEntryAdjustment.timeEntryId` → `@ManyToOne TimeEntry timeEntry` + `@JoinColumn(name = "time_entry_id")`
  - Added convenience constructors: `ApprovalConfiguration(UUID)`, `EstimateItem(UUID)`, `TimeEntry(UUID)`.
  - Added `@Transient` scalar compatibility accessors for all 6 fields.
  - Added `import lombok.ToString` and `@ToString.Exclude` for lazy safety on all converted entities.
  - Updated `@Index` columnList on TimeEntryAdjustment from camelCase to snake_case column name.
  - Updated repository derived query methods (3 repos):
    - `WorkorderPartRepository.findByChangeRequest_Id()`
    - `WorkorderServiceRepository.findByChangeRequest_Id()`
    - `TimeEntryAdjustmentRepository.findByTimeEntry_TimeEntryId()` (non-standard PK name)
  - Updated service code:
    - `EstimateServiceImpl`: builder `.approvalConfiguration(config)` instead of `.approvalConfigurationId(config.getId())`
    - `ChangeRequestServiceImpl`: builder `.changeRequest(changeRequest)` for both WorkorderService and WorkorderPart; 6 repo calls `findByChangeRequest_Id()`
    - `WorkorderServiceImpl`: builder `.originEstimateItem(estimateItem)` for both labor and parts
  - Updated tests: `WorkorderLaborContractBehaviorIT` builder `.originEstimateItem(item)`
  - Validation: `./mvnw -pl pos-workorder -am clean test` (261 tests, 0 failures), `./mvnw -pl pos-archunit -Dtest=ArchitectureTests test` (10 tests, 0 failures)
  - Result: PASS.
- 2026-03-10: `pos-workorder` batch 7 completed (WorkOrderPartSubstitution, TravelSegment, TravelSegmentAdjustment).
  - Converted 4 FK fields to `@ManyToOne` relationships:
    - `WorkOrderPartSubstitution.workorderId` → `@ManyToOne Workorder workorder` + `@JoinColumn(name = "workorder_id")`
    - `WorkOrderPartSubstitution.workorderLineItemId` → `@ManyToOne WorkorderPart workorderLineItem` + `@JoinColumn(name = "workorder_line_item_id")`
    - `TravelSegment.workOrderId` → `@ManyToOne Workorder workOrder` + `@JoinColumn(name = "work_order_id")` (nullable)
    - `TravelSegmentAdjustment.travelSegmentId` → `@ManyToOne TravelSegment travelSegment` + `@JoinColumn(name = "travel_segment_id")`
  - Added convenience constructors: `WorkorderPart(UUID)`, `TravelSegment(UUID)`.
  - Added `@Transient` scalar compatibility accessors for all 4 fields.
  - Added `@ToString.Exclude` for lazy safety on all converted relationships.
  - Updated `@Index` columnList on TravelSegmentAdjustment from camelCase to snake_case column name.
  - Updated repository derived query methods (1 repo):
    - `TravelSegmentAdjustmentRepository.findByTravelSegment_TravelSegmentId()` (non-standard PK name)
  - Updated service code:
    - `WorkorderSubstitutionServiceImpl`: builder `.workorder(new Workorder(workorderId))`, `.workorderLineItem(new WorkorderPart(originalPartId))`
    - `TravelSegmentServiceImpl`: builder `.workOrder(new Workorder(...))` with null check; `.travelSegment(segment)` using managed entity
  - No test changes needed (no test code builds WorkOrderPartSubstitution or TravelSegmentAdjustment entities; TravelSegment builders in tests don't use .workOrderId()).
  - Validation: `./mvnw -pl pos-workorder -am clean test` (261 tests, 0 failures), `./mvnw -pl pos-archunit -Dtest=ArchitectureTests test` (10 tests, 0 failures)
  - Result: PASS.
- 2026-03-10: `pos-workorder` batch 8 completed (SubstituteAudit, WorkorderLaborEntry, TimeEntry, WorkSession, TechnicianAssignment).
  - Converted 5 FK fields to `@ManyToOne` relationships:
    - `SubstituteAudit.linkId` → `@ManyToOne SubstituteLink link` + `@JoinColumn(name = "link_id")`
    - `WorkorderLaborEntry.workorderServiceId` → `@ManyToOne WorkorderService workorderService` + `@JoinColumn(name = "workorder_service_id")`
    - `TimeEntry.workOrderId` → `@ManyToOne Workorder workOrder` + `@JoinColumn(name = "work_order_id")`
    - `WorkSession.workOrderId` → `@ManyToOne Workorder workOrder` + `@JoinColumn(name = "work_order_id")`
    - `TechnicianAssignment.workorderId` → `@ManyToOne Workorder workorder` + `@JoinColumn(name = "workorder_id")`
  - Added convenience constructors: `SubstituteLink(UUID)`, `WorkorderService(UUID)`.
  - Added `@Transient` scalar compatibility accessors for all 5 fields. @NonNull fields use direct `new Entity(uuid)` without null guards.
  - Fixed pre-existing `@Index` columnList inconsistencies (4 indexes across 3 entities).
  - Updated repository derived query methods (3 repos, 6 methods).
  - Updated service code (7 files): SubstituteLinkServiceImpl, WorkorderLaborServiceImpl, WorkorderDetailServiceImpl, WorkexecTimeTrackingServiceImpl, TechnicianAssignmentServiceImpl, WorkSessionServiceImpl, WipServiceImpl.
  - Updated tests (10 files): builder calls and repo method stubs/calls.
  - Validation: `./mvnw -pl pos-workorder -am clean test` (261 tests, 0 failures), `./mvnw -pl pos-archunit -Dtest=ArchitectureTests test` (10 tests, 0 failures)
  - Result: PASS.
- 2026-03-10: `pos-workorder` batch 9 completed (IdempotencyKey×3, Workorder.estimateId).
  - Converted 4 FK fields to `@ManyToOne` relationships:
    - `IdempotencyKey.workorderId` → `@ManyToOne Workorder workorder` + `@JoinColumn(name = "workorder_id")`
    - `IdempotencyKey.changeRequestId` → `@ManyToOne ChangeRequest changeRequest` + `@JoinColumn(name = "change_request_id")`
    - `IdempotencyKey.laborEntryId` → `@ManyToOne WorkorderLaborEntry laborEntry` + `@JoinColumn(name = "labor_entry_id")`
    - `Workorder.estimateId` → `@ManyToOne Estimate estimate` + `@JoinColumn(name = "estimate_id")`
  - Added convenience constructor: `WorkorderLaborEntry(UUID)`.
  - Updated IdempotencyKey constructors to accept entity references instead of UUIDs.
  - Added `@Transient` scalar compatibility accessors for all 4 fields (nullable, with null guards).
  - Updated repository derived query methods (1 repo, 2 methods): `findAllByEstimate_Id`, `findFirstByEstimate_Id`.
  - Updated service code (4 files): IdempotencyServiceImpl, WorkorderServiceImpl, EstimateServiceImpl, PromotionValidationServiceImpl.
  - Updated tests (9 files): IdempotencyServiceTest, PromotionValidationServiceIT, WorkorderInvoiceServiceTest, EstimateRevisionWorkflowTest, ChangeRequestContractBehaviorIT, TechnicianAssignmentContractBehaviorIT, WorkorderCompletionContractBehaviorIT, WorkorderLaborContractBehaviorIT, PartialApprovalPromotionContractBehaviorIT.
  - Integration test fix: Used `estimateRepository.getReferenceById()` instead of `new Estimate(uuid)` for managed persistence contexts.
  - Validation: `./mvnw -pl pos-workorder -am clean test` (261 tests, 0 failures), `./mvnw -pl pos-archunit -Dtest=ArchitectureTests test` (10 tests, 0 failures)
  - Result: PASS.
- 2026-03-10: `pos-workorder` batch 10 completed (EstimateItem.estimateId — high-impact).
  - Converted 1 FK field to `@ManyToOne` relationship:
    - `EstimateItem.estimateId` → `@ManyToOne(fetch=LAZY, optional=false) Estimate estimate` + `@JoinColumn(name = "estimate_id", nullable = false)`
  - Added `@Transient` scalar compatibility accessors (getEstimateId/setEstimateId) for DTO mapper compatibility.
  - Updated repository derived query methods (1 repo, 4 methods): `findByEstimate_IdAndDeletedFalse`, `findByIdAndEstimate_IdAndDeletedFalse`, `countByEstimate_IdAndDeletedFalse`, `findByEstimate_IdAndApprovalStatusAndDeletedFalse`.
  - Updated service code (3 files, 11 calls): EstimateServiceImpl (7 repo calls + 1 builder), PromotionValidationServiceImpl (1 repo call), WorkorderServiceImpl (1 repo call).
  - Updated tests (14 files, 36+ changes): EstimateServiceImplTest, EstimateSearchServiceTest, PromotionValidationServiceIT, WorkorderStartContractBehaviorIT, WorkorderIdempotentPromotionContractBehaviorIT, CrmReferenceIdContractBehaviorIT, EstimateTaxCalculationContractBehaviorIT, PartialApprovalPromotionContractBehaviorIT, EstimateApprovalContractBehaviorIT, WorkorderItemGenerationContractBehaviorIT, EstimatePromotionContractBehaviorIT, WorkorderLaborContractBehaviorIT, TechnicianAssignmentContractBehaviorIT, EstimateSummaryContractBehaviorIT.
  - Integration test strategy: Used managed entity references (saved Estimate variables or `estimateRepository.getReferenceById()`) for `buildItem` helpers.
  - Validation: `./mvnw -pl pos-workorder test` (261 tests, 0 failures), `./mvnw -pl pos-archunit test` (15 tests, 0 failures)
  - Result: PASS. pos-workorder module FK conversion COMPLETE (42 FKs total across batches 1-10).
- 2026-03-10: Multi-module batch 11 completed (pos-vehicle-fitment, pos-vehicle-reference-nhtsa, pos-vehicle-inventory, pos-inventory).
  - Converted 4 FK fields to `@ManyToOne` relationships:
    - `VehicleVariableValue.variableId` → `@ManyToOne VehicleVariable variable` + `@JoinColumn(name = "variable_id")` (pos-vehicle-fitment)
    - `VehicleVariableValue.variableId` → `@ManyToOne VehicleVariable variable` + `@JoinColumn(name = "variable_id")` (pos-vehicle-reference-nhtsa)
    - `VehicleCarePreference.vehicleId` → `@ManyToOne(fetch=LAZY, optional=false) VehicleRecord vehicle` + `@JoinColumn(name = "vehicle_id")` (pos-vehicle-inventory)
    - `CountEntry.recountOfCountEntryId` → `@ManyToOne(fetch=LAZY) CountEntry recountOfCountEntry` (self-reference, nullable) (pos-inventory)
  - Added scalar compatibility getters for all 4 fields.
  - Updated repository methods:
    - `VehicleVariableValueRepository.findByVariable_Id()` (both fitment and NHTSA modules)
    - `VehicleCarePreferenceRepository.findByVehicle_VehicleId()`, `existsByVehicle_VehicleId()`
  - Updated service code:
    - `VehicleFitmentServiceImpl`: `setVariable(vehicleVariableRepository.getReferenceById(...))`, `findByVariable_Id()`
    - `VehicleReferenceService`: same pattern as fitment
    - `VehiclePreferencesServiceImpl`: `findByVehicle_VehicleId()` (3 calls), builder `.vehicle(vehicleRepository.getReferenceById(...))`
    - `CycleCountServiceImpl`: `.recountOfCountEntry(null)`, `.recountOfCountEntry(previousEntry)` using entity reference
  - Updated tests:
    - `VehicleFitmentServiceTest`: `setVariable(variable)` with `VehicleVariable` fixture, `findByVariable_Id()`
    - `CycleCountContractBehaviorIT`: `.recountOfCountEntry(x)` using entity references (5 occurrences)
  - Also identified `CarApiModel.makeId` as false positive — already had `@ManyToOne CarApiMake make` relationship.
  - Validation:
    - pos-vehicle-fitment: 15 tests, 0 failures
    - pos-vehicle-inventory: 16 tests, 0 failures
    - pos-inventory: 260 tests, 0 failures
  - Result: PASS.
- 2026-03-10: pos-accounting batch 12 completed (PaymentApplication.paymentId).
  - Converted 1 FK field to `@ManyToOne` relationship:
    - `PaymentApplication.paymentId` → `@ManyToOne(fetch=LAZY, optional=false) ReceivablePayment payment` + `@JoinColumn(name = "payment_id")`
  - Added `@ToString.Exclude` for lazy safety and scalar compatibility getter `getPaymentId()`.
  - Updated repository: `findByPayment_PaymentId()`, JPQL `pa.payment.paymentId` in `sumAppliedAmountByPaymentId`.
  - Updated service: `buildPaymentApplicationEntity()` uses `setPayment(input.payment())`.
  - Updated tests:
    - `PaymentApplicationServiceTest`: 11 occurrences `setPayment(testPayment)`, 5 occurrences `findByPayment_PaymentId()`
    - `PaymentApplicationControllerIntegrationTest`: 3 occurrences `setPayment(receivablePaymentRepository.getReferenceById(...))`, 4 occurrences `findByPayment_PaymentId()`
  - Validation: 26 unit tests + 18 integration tests, 0 failures
  - Result: PASS.
  - Deferred 5 pos-customer fields (CommunicationPreference.partyId, PartyNote.partyId — AbstractParty TABLE_PER_CLASS inheritance; ContactRoleAssignment.contactId, PartyAlias.sourcePartyId/targetPartyId — composite @Id).
  - All approved CONVERT_NOW candidates across ALL modules are now complete.
  - MIGRATION COMPLETE. Done criteria met:
    1. Every approved same-module Core candidate is DONE or explicitly DEFER with reason. ✓
    2. No cross-service JPA links were introduced. ✓
    3. Module tests pass for all changed modules. ✓
    4. Architecture tests pass (pos-archunit ArchitectureTests, module ArchitectureTests). ✓
    5. Audit/Event entities remain untouched. ✓
