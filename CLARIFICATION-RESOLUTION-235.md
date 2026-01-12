# Clarification Resolution for Issue #235 (Origin #38)

## Clarification Issue
**Issue #235**: [CLARIFICATION] Origin #38: [BACKEND] [STORY] Topology: Define Default Staging and Quarantine Locations for Receiving

## Origin Story
**Issue #38**: [BACKEND] [STORY] Topology: Define Default Staging and Quarantine Locations for Receiving

## Date Resolved
2026-01-12

## Questions and Resolutions

### Question 1: CRITICAL - Domain Conflict
**Question**: This story combines configuration (`domain:location`/`inventory`) and process execution (`domain:workexec`). Should this be split into two stories as recommended in the **Domain Conflict Summary**?

**Resolution**: ✅ **CONFIRMED. The story must be split.**

**Required Split**:
1. **Story A — Configuration (domain:location / domain:inventory)**
   - Define and manage:
     - default **Staging** location
     - default **Quarantine** location
   - Validate constraints
   - Persist configuration with audit metadata

2. **Story B — Execution (domain:workexec / receiving)**
   - Receiving workflow **consumes** the configured defaults
   - No configuration logic, validation rules, or permission modeling lives here

**Rationale**:
- Configuration is **static policy/state**
- Receiving is **process execution**
- Mixing them:
  - violates service boundaries
  - complicates ownership
  - makes reuse and testing harder

---

### Question 2: Uniqueness Rule
**Question**: Can the same `StorageLocation` be designated as both the default Staging and default Quarantine location?

**Resolution**: ✅ **CONFIRMED: NO, the same StorageLocation must not be both.**

**Enforced Business Rule**:
- A `StorageLocation` **cannot** be designated as both:
  - `isDefaultStaging = true`
  - `isDefaultQuarantine = true`

**Enforcement**:
- Validate at configuration time
- Reject configuration changes that violate this rule with a clear error:
  - `DEFAULT_LOCATION_ROLE_CONFLICT`

**Rationale**:
- Prevents operational ambiguity
- Enforces physical and procedural separation
- Simplifies training, audits, and exception handling

---

### Question 3: Permission Model
**Question**: The story mentions "Quarantine moves require permission." Is the definition and enforcement of this permission in scope for this configuration story?

**Resolution**: ✅ **CONFIRMED: Out of scope for this configuration story.**

**Scope Decision**:
- This story **does not define or enforce permissions**
- It only defines which locations are staging/quarantine defaults

**Where Permissions Belong**:
- Permission definition and enforcement (e.g., `INVENTORY_MOVE_FROM_QUARANTINE`) belong to:
  - **domain:security** (permission registry, role mapping), and
  - **domain:inventory** (enforcement during move execution)

**Implication for This Story**:
- Configuration may mark a location as `QUARANTINE`
- Receiving / Inventory execution stories must:
  - check permissions when moving stock **out of** quarantine

---

## Action Items

### 1. Split Issue #38 into Two Stories

#### Story A (Configuration) - Update Issue #38
- Update title to reflect configuration focus
- Remove execution-related acceptance criteria
- Add domain labels: `domain:location`, `domain:inventory`
- Update story body with clarifications
- Remove blocking labels
- Add `status:ready-for-dev` label

#### Story B (Execution) - Create New Issue
- Title: "[BACKEND] [STORY] Receiving: Use Site-Default Staging Location"
- Focus: Receiving workflow consumes configuration
- Add dependency on Story A (Issue #38)
- Add domain labels: `domain:workexec`
- Reference the configuration story

### 2. Close Clarification Issue #235
- Post resolution summary
- Reference updated story #38 and new story B
- Close as resolved

---

## Summary
These decisions cleanly resolve the domain conflict and keep responsibilities well-defined:
- ✅ **Split the story**: configuration vs execution
- ✅ **Uniqueness enforced**: staging ≠ quarantine
- ✅ **Permissions out of scope** here; handled by security/inventory execution stories
