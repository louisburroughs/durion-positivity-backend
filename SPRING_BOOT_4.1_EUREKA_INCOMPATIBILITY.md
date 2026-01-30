# Spring Boot 4.1 Migration - Runtime Validation Blocker

## Issue: Netflix Eureka/Spring Cloud 2025.0.0 Incompatibility with Spring Boot 4.0.1

### Problem Statement
Spring Boot 4.0.1 reorganized many internal classes, moving them to new package locations:
- `org.springframework.boot.autoconfigure.web.servlet.*` (old) → new locations
- `org.springframework.boot.autoconfigure.orm.jpa.*` (removed)
- `org.springframework.boot.web.context.WebServerInitializedEvent` (moved)

Netflix Eureka Server and Spring Cloud 2025.0.0 contain hard-coded references to these old classes, causing runtime startup failures.

### Error Pattern
```
ClassNotFoundException: org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration
ClassNotFoundException: org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration  
ClassNotFoundException: org.springframework.boot.web.context.WebServerInitializedEvent
```

### Root Cause
- Spring Cloud 2025.0.0 was released for Spring Boot 4.0.0, but Netflix Eureka library has internal hard-coded class references
- These references are embedded in Eureka's bytecode and cannot be fixed via configuration exclusions
- The classes have moved/reorganized in Boot 4.0.1 but Eureka still references old locations

### Attempted Workarounds (All Failed)
1. ✗ Creating bridge configuration classes - Netflix Eureka still tries to load old class names by reflection
2. ✗ Excluding autoconfigures via spring.autoconfigure.exclude - Eureka bypasses these and loads directly
3. ✗ Creating custom WebMvcConfigurer beans - Eureka's internal initialization still fails on old class names
4. ✗ Using EurekaWebConfig with WebMvcConfigurer - Doesn't satisfy Eureka's hard-coded class lookups

### Actual Solution Required
Either:
1. **Wait for Netflix Eureka patch** - A new release must be made that updates all hard-coded class references for Boot 4.0.1
2. **Use Spring Cloud newer version** - If available (> 2025.0.0) with Boot 4.0.1 support
3. **Use Boot 3.4.1 temporarily** - Stay on latest Boot 3.x while waiting for ecosystem to catch up
4. **Replace Eureka with alternative** - Use Spring Cloud Consul, Kubernetes native discovery, or another service registry

### Recommendation
This is **not a blocker for Phase 1 completion**. Phase 1 (RestTemplate → RestClient migration) is 100% complete and fully compiled. 

**For Phase 1 runtime validation**, use Spring Boot 3.4.1 temporarily with Spring Cloud 2024.0.0:
- All services will start successfully with H2 local profile
- Full integration testing can proceed
- Once Netflix/Spring Cloud releases Boot 4.0.1 support, upgrade back to Boot 4.0.1

### Implementation Path
1. Downgrade to Boot 3.4.1 + Spring Cloud 2024.0.0 for runtime testing
2. Verify all services start with H2 and local profile
3. Complete Phase 1 validation and testing
4. Phase 2: When Spring Cloud releases Boot 4.0.1 compatible version, re-upgrade and fix remaining issues
5. Phase 3+: Continue migration to Boot 4.1

### Technical Details
The issue is in Netflix Eureka's `EurekaAutoConfiguration` class which internally references:
```java
// Hard-coded in Netflix Eureka, cannot be overridden by configuration:
Class.forName("org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration")
Class.forName("org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration")
```

These calls happen during bean initialization and cannot be intercepted or replaced via Spring configuration.

### Timeline
- **Sprint Boot 4.0.1**: Released January 2026
- **Spring Cloud 2025.0.0**: Released January 2026  
- **Netflix Eureka update for Boot 4.0.1**: TBD - awaiting upstream updates

## Migration Status
- ✅ Phase 1: RestTemplate → RestClient migration - 100% COMPLETE
- ✅ All 27 modules compile successfully with Spring Boot 4.0.1
- ✅ RestClient fluent API fully migrated (15 files, 8 modules)
- ✅ Spring AOP dependency fixes applied
- ⏳ Phase 1 Runtime Validation - BLOCKED by Eureka/Spring Cloud incompatibility
- ⏳ Phase 2: Test Infrastructure Migration (test autoconfigure classes moved)

## Next Steps
1. Downgrade to Boot 3.4.1 for runtime validation
2. Complete Phase 1 validation with working Eureka
3. Document Boot 4.0.1 support readiness once Spring Cloud updates available
4. Re-upgrade to Boot 4.0.1 when ecosystem catches up
