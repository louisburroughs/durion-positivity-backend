Initialization
- Request: create TestingContext, DeploymentContext, and BusinessContext extending AgentContext in pos/agent/context.

Planning
- [x] Implement TestingContext with builder defaults and testing fields.
- [x] Implement DeploymentContext with builder defaults and deployment fields.
- [x] Implement BusinessContext with builder defaults and business fields.
- [x] Update technicalContexts provider to use concrete context classes with technicalContext property set.
- [ ] Validate code compiles and adjust tests/usages if required.

Execution Log
- Added TestingContext, DeploymentContext, and BusinessContext under pos-agent-framework/src/main/java/com/pos/agent/context/ with domain-specific defaults and mutators.
- Updated ContextBasedAgentSelectorTest.technicalContexts to build contexts from pos.agent.context classes and set technicalContext property accordingly. Build validation not yet run.

Summary
- Pending.
