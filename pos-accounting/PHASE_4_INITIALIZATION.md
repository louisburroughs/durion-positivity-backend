---
title: Phase 4: Production Hardening & Cross-Domain Integration
date: 2026-01-24
status: in-progress
phase: 4
---

## Request
Start implementation of Phase 4 (Production Hardening & Cross-Domain Integration) - integrate accounting services with other Durion domains, optimize performance, add observability instrumentation, and prepare for production deployment.

## Action Plan

1. **Integration Test Execution** – Run all Phase 3 backend + frontend tests, document results
2. **Cross-Domain Integration Testing** – Test with Billing, Order, Inventory, People domains
3. **Performance Optimization** – Optimize GL mapping resolution and journal entry posting
4. **Observability Instrumentation** – Add OpenTelemetry tracing, Micrometer metrics, dashboards
5. **Advanced Features Foundation** – Create GL balance dashboard, define bulk import + approval workflows
6. **Production Deployment Preparation** – Create deployment checklist, runbooks, training materials

## Tasks

### Phase 4.0: Integration Test Execution (Days 1-2)
- [ ] **Task 1:** Run AccountingServiceIntegrationTest.java (29 backend tests)
  - Verify all REST endpoints return correct HTTP status codes
  - Validate error response formats
  - Check permission enforcement
  - Record test execution time and performance metrics
  - **Acceptance Criteria:** All 29 tests passing, code coverage ≥ 85%

- [ ] **Task 2:** Run AccountingRestServicesIntegrationTest.groovy (30+ frontend tests)
  - Verify Moqui service wrappers call backend correctly
  - Validate JWT token forwarding
  - Test permission enforcement at Moqui layer
  - **Acceptance Criteria:** All 30+ tests passing, no deprecation warnings

- [ ] **Task 3:** Document test execution results
  - Create TEST_EXECUTION_REPORT.md
  - Record latency metrics (p50, p95, p99) for critical operations
  - Document any failing tests and remediation plan
  - Create performance baseline for Phase 4.2 comparison
  - **Acceptance Criteria:** Report complete, no blocking failures

### Phase 4.1: Cross-Domain Integration Testing (Days 3-5)
- [ ] **Task 4:** Billing domain integration testing
  - Test invoice.posted event flow (Accounting receives and processes)
  - Test payment.posted event flow
  - Test dimension synchronization (Organization → Accounting)
  - Verify GL balance reconciliation with invoice totals
  - **Acceptance Criteria:** 3 scenarios passing, GL balances accurate

- [ ] **Task 5:** Order domain integration testing
  - Test sales.order.shipped event flow
  - Test sales.order.returned event flow
  - Verify GL revenue accounts updated correctly
  - **Acceptance Criteria:** 2 scenarios passing, GL accounts reconciled

- [ ] **Task 6:** Inventory domain integration testing
  - Test inventory.stock.received event flow
  - Test inventory.adjustment event flow
  - Verify GL inventory accounts reconciled with physical counts
  - **Acceptance Criteria:** 2 scenarios passing, inventory GL reconciled

- [ ] **Task 7:** People domain integration testing
  - Test vendor master data integration
  - Test employee reference data integration
  - Verify dimension resolution with people attributes
  - **Acceptance Criteria:** Vendor + employee data accessible

### Phase 4.2: Performance Optimization (Days 6-7)
- [ ] **Task 8:** Optimize GL mapping resolution
  - Add database indexes (temporal + dimensional)
  - Implement Caffeine caching (TTL: 1 hour)
  - Optimize query patterns (cursor-based pagination)
  - Load test with 10k+ mappings
  - **Acceptance Criteria:** Latency < 50ms (p95), cache hit > 90%

- [ ] **Task 9:** Optimize journal entry posting
  - Implement batch posting (up to 1000 entries)
  - Optimize balance validation queries
  - Avoid SELECT N+1 issues
  - Load test with 1000 concurrent entries
  - **Acceptance Criteria:** Single entry < 100ms, batch < 5 sec, no race conditions

- [ ] **Task 10:** Create performance baseline document
  - Document latency metrics (p50, p95, p99) for all operations
  - Define SLO targets
  - Expose metrics via /actuator/prometheus
  - Create PERFORMANCE_BASELINE.md (50-60 lines)
  - **Acceptance Criteria:** Baseline documented, metrics exposed

### Phase 4.3: Observability & Monitoring (Days 8-9)
- [ ] **Task 11:** Add OpenTelemetry instrumentation
  - Configure OTEL agent (automatic REST instrumentation)
  - Add custom spans (5+ critical operations)
  - Include trace attributes (organizationId, operation type, error codes)
  - Propagate W3C trace context to other domains
  - **Acceptance Criteria:** Tracing working, W3C context propagated

- [ ] **Task 12:** Add Micrometer metrics + dashboards
  - Expose latency histograms (all endpoints)
  - Expose error rate counters (by error code)
  - Expose cache hit ratio gauges
  - Create 3 Grafana dashboards (health, business metrics, technical)
  - **Acceptance Criteria:** Metrics exposed via prometheus, dashboards working

- [ ] **Task 13:** Configure production alerts
  - Alert if error rate > 1% for 5 minutes
  - Alert if latency p95 > 500ms for 10 minutes
  - Alert if service unreachable
  - Alert if event processing latency > 500ms (p95)
  - Configure alert routing (Slack/email)
  - **Acceptance Criteria:** Alerts configured and tested

### Phase 4.4: Advanced Features Foundation (Day 10)
- [ ] **Task 14:** Create GL balance dashboard screen
  - New screen: GLBalanceDashboard.xml
  - Service wrapper: durion.getGLBalances (aggregated)
  - Filter by account type, trending view, drill-down to entries
  - Performance target: < 200ms to render
  - **Acceptance Criteria:** Dashboard working, performance acceptable

- [ ] **Task 15:** Document bulk import + approval workflow specs
  - Create BULK_IMPORT_SPECIFICATION.md (CSV schemas, validation rules)
  - Create APPROVAL_WORKFLOW_SPECIFICATION.md (approval matrix, request/response schemas)
  - Define operations requiring approval (post entries > $X, publish rules)
  - **Acceptance Criteria:** Specifications documented, approved by team

## Completed Work

(Will be updated as Phase 4 progresses)

## Current Status

🔄 **Phase 4 In Planning** (1 day completed: plan created, tasks defined)

**Next Immediate Steps:**
1. Run Phase 3 integration tests to establish baseline (Task 1-3)
2. Coordinate with Billing, Order, Inventory teams for cross-domain testing
3. Schedule performance profiling session
4. Set up OTEL/Grafana infrastructure

---

**For Questions:** Contact Accounting Domain Lead or review [PHASE_4_PLAN.md](PHASE_4_PLAN.md) for detailed deliverables and acceptance criteria.

**Target Completion:** February 7, 2026 (2 weeks)
