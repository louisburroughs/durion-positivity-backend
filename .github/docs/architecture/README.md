# Architecture Documentation

This directory contains architectural documentation for the Durion Positivity POS Backend system.

## Table of Contents

### System Architecture
- [Moqui RACI](./moqui-RACI.md) - RACI matrix for Moqui implementation
- [Moqui Domain RACI](./moqui-domain-RACI.md) - Domain-level RACI matrix
- [Moqui Prototype Plan](./moqui-prototype-plan.md) - Prototype implementation plan
- [Prototype Plan](./prototype-plan.md) - General prototype planning
- [Project Timeline](./project-timeline.md) - Project timeline and milestones

### Domain Models and Workflows

#### Customer Approval Workflow (New - 2026-01-08)
- [Approval Workflow Clarification](./approval-workflow-clarification.md) - Complete clarification Q&A for digital customer approvals
- [Approval Domain Model](./approval-domain-model.md) - Technical specifications for approval entities
- [Story Authoring Agent Summary #207](./story-authoring-agent-summary-207.md) - Actionable summary for story updates

The Customer Approval Workflow documentation suite provides comprehensive guidance for implementing digital customer approvals for Estimates and Work Orders, including:
- Generic approval system design
- Dual-format signature capture (PNG + JSON)
- Complete state machine with 6 states
- Estimate versioning rules
- Work order cascade logic
- Audit trail requirements

### Diagrams
- [Domain Diagram - POS](./DomainDiagram_POS.drawio.png)
- [Domain Diagram - Domain Layers](./DomainDiagram_POS-Domain%20Layers.drawio.png)
- [Domain Diagram - With External](./DomainDiagram_POS-DomainLayerWExternal.drawio.png)

## Document Status

### Active Documents (Current)
- ✅ Customer Approval Workflow suite (2026-01-08)
- ✅ Moqui implementation plans
- ✅ Project timeline

### In Progress
- Refer to open issues for ongoing architectural work

## How to Use This Documentation

### For Story Authoring Agents
- Review the Story Authoring Agent Summary documents for actionable updates
- Reference domain models for technical specifications
- Use clarification documents to understand design decisions

### For Developers
- Start with domain model documents for entity definitions
- Review workflow clarification documents for business rules
- Check diagrams for system context

### For Product Owners
- Review clarification documents for feature scope
- Check acceptance criteria in summary documents
- Reference state machines for workflow understanding

## Contributing

When adding new architectural documentation:
1. Place files in this directory
2. Update this README with a link and brief description
3. Include creation date in document metadata
4. Cross-reference related documents

## Related Documentation

- **Governance**: `..github/docs/governance/` - Governance policies and procedures
- **Operations**: `/docs/OperationsRunbook.md` - Operational procedures
- **Code**: Module-specific README files in each service directory

---

*Last Updated: 2026-01-08*
