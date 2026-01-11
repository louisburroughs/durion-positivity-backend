# Clarification Issue #227 - Process Flow

## Current State Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                     CLARIFICATION ISSUE #227                    │
│                                                                 │
│  Created: 2026-01-05                                           │
│  Question: Soft vs Hard Allocation Logic?                      │
│  Status: ⏸️  WAITING FOR ANSWER                                │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │  No answer provided
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│               STORY AUTHORING AGENT REVIEW                      │
│                                                                 │
│  Date: 2026-01-11                                              │
│  Found: NO ANSWERS                                             │
│  Decision: BLOCK (Do Not Guess)                                │
│  Action: Document & Wait                                        │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │  Protocol: "SHALL NOT GUESS"
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                  ARTIFACTS CREATED                              │
│                                                                 │
│  ✅ clarification-227-summary.md                               │
│  ✅ clarification-227-status.md                                │
│  ✅ clarification-227-metadata.json                            │
│  ✅ README-CLARIFICATION-227.md                                │
│  ✅ agent-decision-report.md                                   │
│  ✅ WORK_SUMMARY.md                                            │
│  ✅ PROCESS_FLOW.md (this file)                                │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    CURRENT STATUS                               │
│                                                                 │
│  🔴 BLOCKED - AWAITING BUSINESS DECISION                       │
│                                                                 │
│  Origin Story #29: Still blocked                               │
│  Integration: Cannot proceed                                    │
│  Development: Cannot start                                      │
└─────────────────────────────────────────────────────────────────┘
```

## What Needs to Happen Next

```
┌─────────────────────────────────────────────────────────────────┐
│            👤 HUMAN ACTION REQUIRED                            │
│                                                                 │
│  Who: Business Owner / Product Manager                         │
│  What: Answer the question in Issue #227                       │
│  Where: https://github.com/.../issues/227                      │
│                                                                 │
│  Decision Format:                                              │
│  ┌───────────────────────────────────────────────────┐         │
│  │ **Decision:** [Option A/B/C or custom]           │         │
│  │ **Rationale:** [Explanation]                      │         │
│  │ **Details:** [Edge cases, config, etc.]          │         │
│  └───────────────────────────────────────────────────┘         │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │  Answer provided
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│         🤖 AGENT RESUMES (AUTOMATIC)                           │
│                                                                 │
│  1. Parse business decision from comment                        │
│  2. Update origin story #29 with:                              │
│     • Resolved business rules                                   │
│     • Functional behavior                                       │
│     • Acceptance criteria                                       │
│     • Data model changes                                        │
│  3. Remove blocked:clarification label                          │
│  4. Close clarification issue #227                             │
│  5. Set status:needs-review or status:ready-for-dev            │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │  All checks pass
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    ✅ STORY READY                              │
│                                                                 │
│  Origin Story #29: Fully specified                             │
│  Acceptance Criteria: Complete                                  │
│  Data Model: Defined                                           │
│  Development: Can begin                                         │
└─────────────────────────────────────────────────────────────────┘
```

## Decision Options - Impact Analysis

### Option A: Time-Based Lifecycle
```
Soft Allocation ──[24 hours]──> Hard Allocation
                   (automatic)

Implementation Impact:
  • Need timer/scheduler job
  • Add expiration timestamps
  • Handle auto-conversion logic
  • Test time-based scenarios
```

### Option B: Work Order Type Policy
```
Customer-Pay Work Order ──────> Hard Allocation (immediate)
Internal/Warranty Work Order ─> Soft Allocation (flexible)

Implementation Impact:
  • Add work order type lookup
  • Implement type-based rules
  • Configure type mappings
  • Test per-type scenarios
```

### Option C: Manual User Action
```
Soft Allocation ──[User Action]──> Hard Allocation
                   (explicit)

Implementation Impact:
  • Build UI for conversion
  • Add user permissions
  • Create manual workflow
  • Test user actions
```

## Why Each Option Matters

### If Decision is A (Time-Based)
```
✅ Need: Background job infrastructure
✅ Need: Expiration/cleanup logic
✅ Need: Time-based test fixtures
❌ Don't need: User workflow
❌ Don't need: Type-based rules
```

### If Decision is B (Type-Based)
```
✅ Need: Work order type integration
✅ Need: Policy configuration
✅ Need: Type validation
❌ Don't need: Timer infrastructure
❌ Don't need: User conversion UI
```

### If Decision is C (Manual)
```
✅ Need: User interface
✅ Need: Permission system
✅ Need: User workflow
❌ Don't need: Automatic conversion
❌ Don't need: Timer logic
```

## Risk of Guessing (Why Agent Blocked)

```
If Agent Guessed Option A:
  └─> Build time-based system
      └─> Real answer is B or C
          └─> WRONG IMPLEMENTATION
              └─> Wasted effort + Refactoring needed

If Agent Guessed Option B:
  └─> Build type-based system
      └─> Real answer is A or C
          └─> WRONG IMPLEMENTATION
              └─> Wasted effort + Refactoring needed

If Agent Guessed Option C:
  └─> Build manual workflow
      └─> Real answer is A or B
          └─> WRONG IMPLEMENTATION
              └─> Wasted effort + Refactoring needed

By BLOCKING and WAITING:
  └─> Get correct answer first
      └─> Build right solution
          └─> NO WASTED EFFORT ✅
```

## Protocol Compliance Flow

```
┌────────────────────────────────────────┐
│  Story Authoring Agent Protocol        │
│  Section 7: Clarification Protocol     │
└────────────────────────────────────────┘
              │
              ▼
┌────────────────────────────────────────┐
│  Detect insufficient information?      │
│  YES: Soft vs Hard logic undefined     │
└────────────────────────────────────────┘
              │
              ▼
┌────────────────────────────────────────┐
│  MUST: Stop story finalization         │
│  ✅ DONE                               │
└────────────────────────────────────────┘
              │
              ▼
┌────────────────────────────────────────┐
│  MUST: Create clarification issue      │
│  ✅ DONE (Issue #227)                  │
└────────────────────────────────────────┘
              │
              ▼
┌────────────────────────────────────────┐
│  MUST: Link to origin story            │
│  ✅ DONE (In tracking docs)            │
└────────────────────────────────────────┘
              │
              ▼
┌────────────────────────────────────────┐
│  MUST: Mark as blocked                 │
│  ✅ Should have blocked:clarification  │
└────────────────────────────────────────┘
              │
              ▼
┌────────────────────────────────────────┐
│  SHALL NOT: Guess                      │
│  ✅ DID NOT GUESS                      │
└────────────────────────────────────────┘
              │
              ▼
┌────────────────────────────────────────┐
│  Result: ✅ FULLY COMPLIANT            │
└────────────────────────────────────────┘
```

## Timeline Visualization

```
Jan 5, 2026
    │
    │  Clarification Issue #227 Created
    ▼
┌───────┐
│  #227 │  Question: Soft vs Hard allocation?
└───────┘  Status: OPEN, waiting for answer
    │
    │  ... 6 days pass ...
    │
Jan 11, 2026
    │
    │  Story Authoring Agent Reviews
    ▼
┌───────┐
│ Agent │  Finds: NO ANSWERS
└───────┘  Action: Document & Block
    │
    │  Creates artifacts
    ▼
┌───────┐
│ Docs  │  7 files created
└───────┘  Status: BLOCKED
    │
    │  Waiting...
    ▼
    ?
    │
    │  Human provides answer
    ▼
┌───────┐
│Answer │  Decision made
└───────┘  Integration can proceed
    │
    │  Agent resumes automatically
    ▼
┌───────┐
│Updated│  Story #29 finalized
└───────┘  Ready for development
```

## Summary

**Current Position:** ⏸️ Paused, waiting for human decision  
**Reason:** Critical business rule undefined  
**Action Required:** Business owner must answer in issue #227  
**Next Phase:** Automatic integration after answer provided  

**Status:** ✅ Process working as designed  
**Compliance:** ✅ Agent followed protocol correctly  
**Risk:** 🟢 Properly managed by blocking

---

**Document Purpose:** Visual guide to process flow and decision impact  
**Created:** 2026-01-11  
**Updated:** 2026-01-11
