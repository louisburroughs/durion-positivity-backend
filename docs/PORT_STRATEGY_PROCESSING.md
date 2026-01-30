# Port Strategy Implementation — durion-positivity-backend

## Request Summary

Implement dynamic port strategy per policy:
- Dynamic ports (`server.port: 0`) for development services
- Stable gateway on port 8080 as single external entry point
- Eureka on 8761 for service discovery
- Separate management ports (internal-only)
- Fix port conflicts and missing configurations

## Action Plan

### Phase 1: Documentation & Foundation
- Create PORT_STRATEGY.md documenting baseline, fixed ports, and profiles
- Create spring-cloud-discovery-enabled-false profile pattern
- Update DOCKER_CONFIGURATION.md with gateway-only fixed ports note

### Phase 2: Boilerplate & Missing Configs
- Add application.yml boilerplate for pos-invoice
- Add application.yml boilerplate for pos-inquiry

### Phase 3: Fix Critical Port Conflicts
- Fix pos-accounting: application.yml (8096), docker-compose (9001) mismatch
- Fix pos-agent-framework: duplicate port 8080

### Phase 4: Create Local Profile Templates
- Create application-local.yml base template
- Deploy to all pos-* modules (server.port: 0, management.server.port: internal)

### Phase 5: Update application.yml Base Files
- Remove fixed hardcoded ports from all service application.yml files
- Keep only spring.application.name and eureka config
- Add spring.profiles.active to reference local profile

### Phase 6: Update Docker Compose & CI
- Update compose.yaml (production) — remove hardcoded ports, use Eureka DNS
- Update compose.debug.yaml — same as production + debug port
- Update Maven OpenAPI test profiles — use server.port: 0

### Phase 7: Documentation Updates
- Update DOCKER_CONFIGURATION.md with new strategy
- Update AGENTS.md with port strategy reference
- Add local dev instructions

### Phase 8: Verification & Summary
- Verify all configs are in place
- Document changes in summary

---

## Tracking

- [ ] Phase 1: Documentation & Foundation
- [ ] Phase 2: Boilerplate & Missing Configs
- [ ] Phase 3: Fix Critical Port Conflicts
- [ ] Phase 4: Create Local Profile Templates
- [ ] Phase 5: Update application.yml Base Files
- [ ] Phase 6: Update Docker Compose & CI
- [ ] Phase 7: Documentation Updates
- [ ] Phase 8: Verification & Summary

---

## Summary

*To be completed after all phases.*
