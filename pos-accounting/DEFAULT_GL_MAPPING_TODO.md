# Default GL Mapping - Implementation Checklist

## ✅ Completed
- [x] Database schema and migration
- [x] Entity class with audit fields
- [x] Repository with resolution queries
- [x] Service interface and implementation
- [x] Updated PostingRuleEvaluator to use defaults as fallback
- [x] GL account validation in service layer

### ✅ REST API Layer (COMPLETE)
- [x] Create DTO classes:
  - `DefaultGLMappingRequest` (input)
  - `DefaultGLMappingResponse` (output)
  - `DefaultGLMappingListResponse` (paginated output)
- [x] Create DTO mapper utility: `DefaultGLMappingMapper`
- [x] Create REST controller: `DefaultGLMappingController`
  - POST `/v1/accounting/default-mappings` (create)
  - PUT `/v1/accounting/default-mappings/{id}` (update)
  - DELETE `/v1/accounting/default-mappings/{id}` (deactivate)
  - GET `/v1/accounting/default-mappings/{id}` (get by ID)
  - GET `/v1/accounting/default-mappings` (list with pagination)
  - GET `/v1/accounting/default-mappings/search` (search by event type or org)
  - GET `/v1/accounting/default-mappings/global` (list global defaults)
  - GET `/v1/accounting/default-mappings/resolve` (resolve for event + org)
- [x] Add `@EmitEvent` annotations for audit trail
- [x] Add `@PreAuthorize` security annotations
- [x] Add OpenAPI/Swagger documentation annotations

### ✅ Testing (COMPLETE)
- [x] Unit tests for `DefaultGLMappingServiceImpl` (341 lines, 8 nested test classes)
- [x] Integration tests for updated `PostingRuleEvaluatorImpl` (243 lines, 3 nested test classes)
- [x] Integration tests for fallback behavior (org-specific → global → fail)
- [x] Integration tests for REST endpoints (DefaultGLMappingControllerTest, 8 nested test classes)

## 📋 Still Needed

### Documentation
- [ ] Update `BACKEND_CONTRACT_GUIDE.md` with default mapping section
- [ ] Update accounting domain README with default mapping patterns
- [ ] Add sample data migration for common event types

### Security & Validation (COMPLETE)
- [x] Add permission checks to REST endpoints (accounting:default-mapping:*)
- [x] Validate unique constraint (eventType + organizationId + active) - handled by DB constraint
- [x] GL account validation before saving (via GLAccountService)

### Configuration & Feature Flags (COMPLETE)
- [x] Create DefaultGLMappingProperties configuration class
- [x] Add feature flag: `pos.accounting.default-mappings.enabled`
- [x] Add feature flag: `pos.accounting.default-mappings.allow-global-defaults`
- [x] Add feature flag: `pos.accounting.default-mappings.require-amount-field`
- [x] Update PostingRuleEvaluatorImpl to respect feature flags
- [x] Create comprehensive feature flag tests (PostingRuleEvaluatorFeatureFlagTest)
- [x] Create configuration documentation (default-gl-mapping-config-example.yml)

## Testing Scenarios

### Happy Path
- [ ] Event with no rule → uses org-specific default → success
- [ ] Event with no rule → uses global default → success
- [ ] Event with empty rulesDefinition → fails (no fake GL accounts)

### Edge Cases
- [ ] Multiple defaults for same eventType (different orgs)
- [ ] Deactivate default → fallback to next level
- [ ] Default with invalid GL account IDs → validation error
- [ ] Zero or missing amount in event payload → uses fallback

### Performance
- [ ] Default mapping lookup performance (indexed queries)
- [ ] Cache default mappings (if high volume)

## Migration Path

### For Existing Deployments
1. Deploy database migration
2. Deploy code with feature flag OFF
3. Configure default mappings via API
4. Test in non-production environment
5. Enable feature flag in production
6. Monitor event processing logs

### For New Deployments
1. Deploy all at once (schema + code)
2. Configure global defaults for common event types
3. Add org-specific defaults as needed

## Monitoring & Alerts

### Metrics to Track
- [ ] Count of events using default mappings vs explicit rules
- [ ] Default mapping resolution failures
- [ ] Default mapping cache hit ratio (if caching added)

### Alerts to Configure
- [ ] Alert when event type has no rule AND no default mapping
- [ ] Alert when default GL account becomes inactive
- [ ] Alert on high volume of default mapping usage (may indicate missing rules)
