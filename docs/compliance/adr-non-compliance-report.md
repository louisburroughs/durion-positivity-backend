# ADR Non-Compliance Report

## Scan Metadata
- timestamp: 2026-02-27T02:51:17Z
- target_repo: durion-positivity-backend
- adr_root: /home/louisb/Projects/durion/docs/adr
- output_format: md
- module_scope: all modules
- severity_threshold: low

## Summary
- ADR rules audited: 5
- findings: 5
- high: 3
- medium: 2
- low: 0

## ADR Rules Audited
| ADR | Rule ID | Check Type | Description |
|-----|---------|------------|-------------|
| ADR-0026 | service_package_interfaces_only | machine-checkable | `com.positivity.{domain}.service..` is public contract surface and must contain interfaces only. |
| ADR-0026 | controllers_no_entity_dependency | machine-checkable | Controllers must not directly depend on entities. |
| ADR-0025 | permissions_manifest_required_for_authorized_modules | machine-checkable | Modules enforcing permissions must register from `src/main/resources/permissions.yaml`. |
| ADR-0009 | internal_package_boundary | machine-checkable | Module internals should reside under `internal/*`, with `service/*` as public API only. |
| ADR-0009 | architecture_test_required | machine-checkable | Each service module should enforce architecture boundaries via ArchUnit tests. |

## Findings

### High

- ID: NC-0001
  - severity: high
  - confidence: high
  - adr_id: ADR-0026
  - rule_id: service_package_interfaces_only
  - module: multiple (`pos-order`, `pos-mcp-server`, `pos-vehicle-fitment`, `pos-vehicle-inventory`, `pos-vehicle-reference-carapi`, `pos-vehicle-reference-nhtsa`)
  - class_name: multiple
  - file: multiple
  - line: multiple
  - evidence:
    - `pos-order/src/main/java/com/positivity/order/service/PriceOverrideServiceImpl.java:32`
    - `pos-mcp-server/src/main/java/com/positivity/mcp/service/ToolRegistrationService.java:15`
    - `pos-mcp-server/src/main/java/com/positivity/mcp/service/SystemPromptService.java:16`
    - `pos-mcp-server/src/main/java/com/positivity/mcp/service/LlmApiConfigService.java:16`
    - `pos-vehicle-fitment/src/main/java/com/positivity/vehiclefitment/service/VehicleFitmentService.java:33`
    - `pos-vehicle-fitment/src/main/java/com/positivity/vehiclefitment/service/VehicleApplicabilityHintService.java:22`
    - `pos-vehicle-inventory/src/main/java/com/positivity/vehicle/service/VehicleService.java:24`
    - `pos-vehicle-inventory/src/main/java/com/positivity/vehicle/service/VehicleSearchService.java:26`
    - `pos-vehicle-inventory/src/main/java/com/positivity/vehicle/service/VehiclePreferencesService.java:25`
    - `pos-vehicle-inventory/src/main/java/com/positivity/vehicle/service/VehicleEventIngestionService.java:36`
    - `pos-vehicle-reference-carapi/src/main/java/com/positivity/vehiclereferencecarapi/service/VehicleReferenceService.java:25`
    - `pos-vehicle-reference-nhtsa/src/main/java/com/positivity/nhtsa/service/VehicleReferenceService.java:21`
  - non_compliance: Public `service/*` packages contain concrete classes/implementations.
  - repair_recommendation: Keep interfaces in `service/*`; move implementations to `internal/service/*` and wire via Spring components.
  - repair_effort: L
  - repair_owner: backend-module-owner

- ID: NC-0002
  - severity: high
  - confidence: high
  - adr_id: ADR-0026
  - rule_id: controllers_no_entity_dependency
  - module: multiple
  - class_name: multiple internal controllers
  - file: multiple
  - line: multiple
  - evidence:
    - `pos-accounting/src/main/java/com/positivity/accounting/internal/controller/JournalEntryController.java:8` imports `internal.entity.JournalEntry`
    - `pos-event-receiver/src/main/java/com/positivity/poseventreceiver/internal/controller/EmitEventController.java:6` imports `internal.entity.EmittedEvent`
    - `pos-event-receiver/src/main/java/com/positivity/poseventreceiver/internal/controller/EventTypeController.java:6` imports `internal.entity.EventType`
    - `pos-vehicle-fitment/src/main/java/com/positivity/vehiclefitment/internal/controller/VehicleFitmentController.java:11-14` imports multiple `internal.entity.*`
    - `pos-vehicle-inventory/src/main/java/com/positivity/vehicle/internal/controller/VehicleController.java:19` imports `internal.entity.VehicleEntity`
    - `pos-vehicle-inventory/src/main/java/com/positivity/vehicle/internal/controller/VehiclePreferencesController.java:4` imports `internal.entity.VehicleCarePreference`
    - `pos-vehicle-reference-carapi/src/main/java/com/positivity/vehiclereferencecarapi/internal/controller/VehicleReferenceController.java:3-4` imports `internal.entity.*`
    - `pos-vehicle-reference-nhtsa/src/main/java/com/positivity/nhtsa/internal/controller/VehicleReferenceController.java:3-6` imports `internal.entity.*`
    - `pos-workorder/src/main/java/com/positivity/workorder/internal/controller/WorkorderController.java:17-20` imports multiple `internal.entity.*`
    - `pos-workorder/src/main/java/com/positivity/workorder/internal/controller/EstimateController.java:37` imports `internal.entity.Workorder`
    - `pos-workorder/src/main/java/com/positivity/workorder/internal/controller/ChangeRequestController.java:9` imports `internal.entity.ChangeRequest`
    - `pos-workorder/src/main/java/com/positivity/workorder/internal/controller/WorkorderLaborController.java:24` imports `internal.entity.WorkorderLaborEntry`
  - non_compliance: Controllers are coupled to entities instead of DTO/service contract models.
  - repair_recommendation: Introduce/expand DTO mappings at service boundary and remove entity imports from controllers.
  - repair_effort: L
  - repair_owner: backend-module-owner

- ID: NC-0003
  - severity: high
  - confidence: high
  - adr_id: ADR-0025
  - rule_id: permissions_manifest_required_for_authorized_modules
  - module: pos-mcp-server
  - class_name: multiple controllers with `@PreAuthorize`
  - file: `pos-mcp-server/src/main/java/com/positivity/mcp/internal/controller/*`
  - line: multiple (11 `@PreAuthorize` occurrences)
  - evidence:
    - `pos-mcp-server/src/main/java/com/positivity/mcp/internal/controller/SystemPromptController.java:35,41,47,54,62`
    - `pos-mcp-server/src/main/java/com/positivity/mcp/internal/controller/LlmApiConfigController.java:35,41,47,54,62`
    - `pos-mcp-server/src/main/java/com/positivity/mcp/internal/controller/McpChatController.java:56`
    - Missing file: `pos-mcp-server/src/main/resources/permissions.yaml`
  - non_compliance: Module enforces authorization without manifest-backed permission registration source.
  - repair_recommendation: Add `permissions.yaml`, adopt `PermissionRegistrationSupport` manifest flow, and align authorities with enforced checks.
  - repair_effort: M
  - repair_owner: security-domain-owner

### Medium

- ID: NC-0004
  - severity: medium
  - confidence: medium
  - adr_id: ADR-0009
  - rule_id: internal_package_boundary
  - module: pos-image
  - class_name:
    - `com.positivity.posimage.controller.ImageController`
    - `com.positivity.posimage.dao.ImageDao`
    - `com.positivity.posimage.dao.ImageDaoImpl`
    - `com.positivity.posimage.model.ImageEntity`
    - `com.positivity.posimage.model.Classification`
    - `com.positivity.posimage.repository.ImageRepository`
  - file: `pos-image/src/main/java/com/positivity/posimage/*`
  - line: package declarations at line 1 across listed files
  - evidence: Primary implementation packages are `controller`, `dao`, `model`, `repository` directly under domain root, not under `internal/*`.
  - non_compliance: Module structure drifts from internal encapsulation pattern expected by platform architecture rules.
  - repair_recommendation: Move implementation packages to `com.positivity.posimage.internal.*`; retain only application class at root and public interfaces in `service/*` if needed.
  - repair_effort: L
  - repair_owner: backend-module-owner

- ID: NC-0005
  - severity: medium
  - confidence: high
  - adr_id: ADR-0009
  - rule_id: architecture_test_required
  - module: pos-tax
  - class_name: N/A (test-suite level non-compliance)
  - file: `pos-tax/src/test/java`
  - line: N/A
  - evidence:
    - Present tests: `TaxCalculationServiceTest`, `TaxAddressValidationTest`, `CurrencyCodeValidationTest`
    - Missing expected architecture enforcement test (no `*Architecture*Test*.java` found in module)
  - non_compliance: Module lacks explicit ArchUnit architecture-boundary enforcement test.
  - repair_recommendation: Add `ArchitectureTest` for package boundaries, layering, and service exposure checks.
  - repair_effort: S
  - repair_owner: backend-module-owner

## Repair Queue
1. NC-0002 - remove controller-to-entity coupling (highest layering risk across multiple modules)
2. NC-0001 - refactor service package implementations out of public `service/*`
3. NC-0003 - add `permissions.yaml` and manifest registration in `pos-mcp-server`
4. NC-0004 - migrate `pos-image` implementation packages into `internal/*`
5. NC-0005 - add ArchUnit `ArchitectureTest` to `pos-tax`

## Open Questions
- Should shared library modules (`pos-security-common`, `pos-shared-dtos`, `pos-tax-common`, `pos-document-helper`) be explicitly exempted from ADR-0009 internal packaging and architecture-test requirements via ADR addendum?
- For vehicle-reference modules currently exposing concrete classes in `service/*`, do you want strict ADR-0026 refactor now or staged migration with temporary exceptions?
