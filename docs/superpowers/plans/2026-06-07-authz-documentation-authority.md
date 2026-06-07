# Authorization Documentation Authority Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish one authoritative cross-repo explanation of how roles, permissions, `perm_bits`, and tokens work together for API authorization, then retire or relabel contradictory documents.

**Architecture:** Keep one normative narrative in `durion` for cross-repo behavior, keep service-local operational guides in `durion-positivity-backend`, and reduce every other document to either a short reference, an ADR, or a clearly historical artifact. Treat current code in `pos-security-service`, `pos-api-gateway`, and `pos-security-common` as the implementation authority while documenting any unresolved contract mismatches explicitly.

**Tech Stack:** Markdown, Spring Boot security modules, Spring Cloud Gateway, JWT, `perm_bits` bitset catalog, repo-local grep/diff verification.

---

### Task 1: Freeze The Actual Runtime Contract

**Files:**
- Review: `pos-security-service/src/main/java/com/positivity/securityservice/internal/service/JwtServiceImpl.java`
- Review: `pos-security-service/src/main/java/com/positivity/securityservice/internal/service/RoleAuthorityServiceImpl.java`
- Review: `pos-security-service/src/main/java/com/positivity/securityservice/internal/service/RoleManagementServiceImpl.java`
- Review: `pos-security-service/src/main/java/com/positivity/securityservice/internal/service/UserServiceImpl.java`
- Review: `pos-security-service/src/main/java/com/positivity/securityservice/internal/service/CustomUserDetailsService.java`
- Review: `pos-api-gateway/src/main/java/com/positivity/gateway/config/SecurityGatewayConfig.java`
- Review: `pos-api-gateway/src/main/java/com/positivity/gateway/config/GatewayPermissionCatalog.java`
- Review: `pos-security-common/src/main/java/com/positivity/security/common/GatewayAuthoritiesFilter.java`
- Review: `pos-security-service/src/main/java/com/positivity/securityservice/internal/controller/AuthController.java`
- Review: `pos-security-service/src/main/java/com/positivity/securityservice/internal/controller/JwtController.java`

- [ ] **Step 1: Record the implemented token claims and lifecycle**

Run: `nl -ba pos-security-service/src/main/java/com/positivity/securityservice/internal/service/JwtServiceImpl.java | sed -n '88,420p'`
Expected: evidence that access tokens include `sub`, `uid`, `roles`, `perm_bits`, `perm_ver`, optional `personId`, and that refresh tokens include `sub`, `uid`, `type=refresh`, `jti`, `iat`, `exp`.

- [ ] **Step 2: Record how permissions are actually derived today**

Run: `nl -ba pos-security-service/src/main/java/com/positivity/securityservice/internal/service/RoleAuthorityServiceImpl.java | sed -n '23,220p'`
Expected: evidence that token permission content is derived from hardcoded role expansion, not only from persisted `role_permissions`.

- [ ] **Step 3: Record the persisted RBAC model that also exists**

Run: `nl -ba pos-security-service/src/main/java/com/positivity/securityservice/internal/service/RoleManagementServiceImpl.java | sed -n '81,260p'`
Expected: evidence that persisted role-to-permission mappings, scope, and effective dating exist and are used by user/role management APIs.

- [ ] **Step 4: Record how effective roles are resolved for login and refresh**

Run: `nl -ba pos-security-service/src/main/java/com/positivity/securityservice/internal/service/UserServiceImpl.java | sed -n '115,143p'`

Run: `nl -ba pos-security-service/src/main/java/com/positivity/securityservice/internal/service/CustomUserDetailsService.java | sed -n '29,56p'`
Expected: evidence that direct user roles plus effective role assignments are merged into role names before token issuance.

- [ ] **Step 5: Record how gateway authorization is enforced**

Run: `nl -ba pos-api-gateway/src/main/java/com/positivity/gateway/config/SecurityGatewayConfig.java | sed -n '224,405p'`

Run: `nl -ba pos-security-common/src/main/java/com/positivity/security/common/GatewayAuthoritiesFilter.java | sed -n '85,219p'`
Expected: evidence that the gateway strips inbound spoofable headers, validates issuer/audience, decodes `perm_bits`, forwards `X-Authorities` and `X-Roles`, and that downstream services expand `PERM_foo` into both raw and plain permission authorities.

- [ ] **Step 6: Record unresolved implementation mismatches that block “authoritative” wording**

Run: `rg -n "CATALOG_VERSION" pos-security-service/src/main/java/com/positivity/securityservice/internal/enums/PermissionCode.java pos-api-gateway/src/main/java/com/positivity/gateway/config/GatewayPermissionCatalog.java`

Run: `nl -ba pos-security-service/src/main/java/com/positivity/securityservice/internal/controller/JwtController.java | sed -n '120,290p'`

Run: `nl -ba pos-security-service/src/main/java/com/positivity/securityservice/internal/dto/TokenPairRequest.java | sed -n '1,40p'`
Expected: evidence of at least these contract hazards:
- `PermissionCode.CATALOG_VERSION` is `7` while `GatewayPermissionCatalog.CATALOG_VERSION` is `6`.
- `/v1/auth/token-pair` accepts `subject` and optional `roles`, not username/password.
- `/v1/auth/validate` is `GET`, not `POST`.
- `/v1/auth/revoke` is `DELETE`, not `POST`.

### Task 2: Decide And Create The Canonical Narrative

**Files:**
- Create: `/home/louis-burroughs/IdeaProjects/durion/docs/architecture/AUTHORIZATION_MODEL.md`
- Modify: `/home/louis-burroughs/IdeaProjects/durion/docs/architecture/API_SECURITY_ARCHITECTURE.md`
- Modify: `/home/louis-burroughs/IdeaProjects/durion/docs/adr/0040-roles-jwt-permission-governance-policy.adr.md`

- [ ] **Step 1: Create the new authoritative document in `durion`**

Document sections to write:
- scope and ownership
- glossary: user, person, role, permission, authority, `perm_bits`, `perm_ver`, `X-Authorities`, `X-Roles`
- login flow
- refresh and revocation flow
- access-token claim contract
- gateway decoding and forwarding
- downstream Spring Security behavior
- current implementation caveats and legacy compatibility
- open risks that must be resolved before claiming the model is stable

Run after writing: `rg -n "^#|^##|perm_bits|X-Authorities|X-Roles|RoleAuthorityServiceImpl|catalog version" /home/louis-burroughs/IdeaProjects/durion/docs/architecture/AUTHORIZATION_MODEL.md`
Expected: one focused document that names the actual classes implementing the contract.

- [ ] **Step 2: Turn the broader API security document into a pointer plus summary**

Update `/home/louis-burroughs/IdeaProjects/durion/docs/architecture/API_SECURITY_ARCHITECTURE.md` so it keeps the high-level boundary view but points readers to `AUTHORIZATION_MODEL.md` for token, role, permission, and `perm_bits` details.

Run after writing: `rg -n "AUTHORIZATION_MODEL|roles|perm_bits|X-Authorities" /home/louis-burroughs/IdeaProjects/durion/docs/architecture/API_SECURITY_ARCHITECTURE.md`
Expected: no duplicate deep explanation; clear link to the new normative document.

- [ ] **Step 3: Tighten ADR-0040 without turning it into an implementation guide**

Update `/home/louis-burroughs/IdeaProjects/durion/docs/adr/0040-roles-jwt-permission-governance-policy.adr.md` so it remains policy-level but links to `AUTHORIZATION_MODEL.md` for implementation detail and explicitly notes that temporary legacy `authorities` fallback still exists in gateway code.

Run after writing: `rg -n "AUTHORIZATION_MODEL|legacy|authorities fallback|perm_bits" /home/louis-burroughs/IdeaProjects/durion/docs/adr/0040-roles-jwt-permission-governance-policy.adr.md`
Expected: policy remains concise, implementation reference is explicit.

### Task 3: Repair Or Retire Stale Documents

**Files:**
- Modify or archive: `/home/louis-burroughs/IdeaProjects/durion/docs/adr/0011-api-gateway-security-architecture.adr.md`
- Modify: `pos-security-service/docs/AUTH_TOKEN_USAGE_GUIDE.md`
- Modify or archive: `pos-security-service/docs/security-service-guide.md`
- Modify or archive: `/home/louis-burroughs/IdeaProjects/durion/domains/security/docs/PERMISSION_REGISTRY.md`
- Modify or archive: `/home/louis-burroughs/IdeaProjects/durion/domains/security/docs/BASELINE_PERMISSIONS.md`
- Modify or archive: `/home/louis-burroughs/IdeaProjects/durion/domains/security/docs/RBAC_POLICY.md`
- Modify or archive: `pos-api-gateway/docs/permissions-encoding.md`

- [ ] **Step 1: Fix ADR-0011 as a superseded claim-contract source**

Do not rewrite the decision history wholesale. Add a clear amendment/supersession note near the top that points claim semantics to ADR-0040 and `AUTHORIZATION_MODEL.md`, because the current ADR text still says access tokens carry `authorities`.

Run after writing: `rg -n "authorities|ADR-0040|AUTHORIZATION_MODEL|supersed" /home/louis-burroughs/IdeaProjects/durion/docs/adr/0011-api-gateway-security-architecture.adr.md`
Expected: readers cannot mistake ADR-0011 as the live token-claim reference.

- [ ] **Step 2: Correct the consumer token guide to match live endpoints**

Update `pos-security-service/docs/AUTH_TOKEN_USAGE_GUIDE.md` so it documents:
- `/v1/auth/login` as the credential flow
- `/v1/auth/token-pair` as subject/role-based token issuance, if retained
- `GET /v1/auth/validate`
- `DELETE /v1/auth/revoke`
- the actual `TokenPairResponse` shape (`accessToken`, `refreshToken`)
- access-token claims and refresh-token omissions

Run after writing: `rg -n "token-pair|login|validate|revoke|accessToken|refreshToken|perm_bits|roles" pos-security-service/docs/AUTH_TOKEN_USAGE_GUIDE.md`
Expected: no endpoint or payload examples that contradict `AuthController`, `JwtController`, or DTOs.

- [ ] **Step 3: Rework the administrator guide around permissions, not human role labels**

Update `pos-security-service/docs/security-service-guide.md` so “Required role(s): Admin” style statements are replaced with required permissions, and add a short explanation that UI roles are coarse signals while APIs enforce permissions.

Run after writing: `rg -n "Required role|Required permission|security:|perm_bits|X-Authorities" pos-security-service/docs/security-service-guide.md`
Expected: operator guidance matches controller `@PreAuthorize` reality.

- [ ] **Step 4: Retire or clearly relabel old domain security docs that describe a different system**

For each of the following, either rewrite to current behavior or prepend a prominent “historical / not authoritative” notice with links to the new canonical docs:
- `/home/louis-burroughs/IdeaProjects/durion/domains/security/docs/PERMISSION_REGISTRY.md`
- `/home/louis-burroughs/IdeaProjects/durion/domains/security/docs/BASELINE_PERMISSIONS.md`
- `/home/louis-burroughs/IdeaProjects/durion/domains/security/docs/RBAC_POLICY.md`
- `pos-api-gateway/docs/permissions-encoding.md`

Run after writing: `rg -n "historical|not authoritative|AUTHORIZATION_MODEL|ADR-0040" /home/louis-burroughs/IdeaProjects/durion/domains/security/docs/PERMISSION_REGISTRY.md /home/louis-burroughs/IdeaProjects/durion/domains/security/docs/BASELINE_PERMISSIONS.md /home/louis-burroughs/IdeaProjects/durion/domains/security/docs/RBAC_POLICY.md pos-api-gateway/docs/permissions-encoding.md`
Expected: none of these files can silently masquerade as the current contract.

### Task 4: Explain The Dual Models Explicitly

**Files:**
- Modify: `/home/louis-burroughs/IdeaProjects/durion/docs/architecture/AUTHORIZATION_MODEL.md`
- Optional note: `pos-security-service/docs/security-service-guide.md`

- [ ] **Step 1: Add a “Current Reality vs Intended Model” section**

Spell out that the code currently has both:
- persisted `roles` + `role_permissions` + `role_assignments` APIs, and
- hardcoded `RoleAuthorityServiceImpl` expansion used for token permission emission.

Run after writing: `rg -n "Current Reality|RoleAuthorityServiceImpl|role_permissions|role_assignments|hardcoded" /home/louis-burroughs/IdeaProjects/durion/docs/architecture/AUTHORIZATION_MODEL.md`
Expected: future readers understand why some docs drifted and where truth is split today.

- [ ] **Step 2: Add the legacy principal-role path as non-primary**

Document `PrincipalRoleController` and `AuthorizationController` as legacy or specialized RBAC-matrix endpoints, not the main request authorization path.

Run after writing: `rg -n "PrincipalRoleController|AuthorizationController|legacy|non-primary" /home/louis-burroughs/IdeaProjects/durion/docs/architecture/AUTHORIZATION_MODEL.md`
Expected: readers do not confuse matrix-style authorization APIs with gateway-enforced runtime authorization.

### Task 5: Add A Repeatable Drift Check

**Files:**
- Create: `scripts/check-authz-doc-drift.sh`
- Modify: `scripts/README.md`
- Optional modify: CI workflow file if a docs-check workflow already exists

- [ ] **Step 1: Add a lightweight drift script**

Script checks should include:
- `PermissionCode.CATALOG_VERSION` equals `GatewayPermissionCatalog.CATALOG_VERSION`
- `AUTH_TOKEN_USAGE_GUIDE.md` examples mention the same HTTP verbs as `JwtController`
- no active docs still describe access tokens as requiring `authorities`
- active docs mention `perm_bits` and `roles` together

Run after writing: `bash scripts/check-authz-doc-drift.sh`
Expected: exit code `0` when docs and code agree; non-zero with actionable mismatch output otherwise.

- [ ] **Step 2: Document how to run the drift check**

Update `scripts/README.md` with a short entry for the new checker.

Run after writing: `rg -n "check-authz-doc-drift" scripts/README.md`
Expected: the checker is discoverable by maintainers.

### Task 6: Verify And Publish

**Files:**
- Verify: all files above

- [ ] **Step 1: Run final targeted verification**

Run: `rg -n -i "authorities claim|POST /security-service/v1/auth/validate|POST /security-service/v1/auth/revoke|/api/permissions/register|Required role\\(s\\):" /home/louis-burroughs/IdeaProjects/durion/docs pos-security-service/docs pos-api-gateway/docs`
Expected: either no matches, or matches only inside explicitly historical documents.

- [ ] **Step 2: Re-read the canonical narrative against the code**

Run: `rg -n "perm_bits|perm_ver|roles|X-Authorities|X-Roles|RoleAuthorityServiceImpl|PrincipalRoleController|AuthorizationController" /home/louis-burroughs/IdeaProjects/durion/docs/architecture/AUTHORIZATION_MODEL.md`
Expected: the new doc covers the runtime flow end-to-end.

- [ ] **Step 3: Commit documentation cleanup intentionally**

```bash
git add /home/louis-burroughs/IdeaProjects/durion/docs/architecture/AUTHORIZATION_MODEL.md \
  /home/louis-burroughs/IdeaProjects/durion/docs/architecture/API_SECURITY_ARCHITECTURE.md \
  /home/louis-burroughs/IdeaProjects/durion/docs/adr/0011-api-gateway-security-architecture.adr.md \
  /home/louis-burroughs/IdeaProjects/durion/docs/adr/0040-roles-jwt-permission-governance-policy.adr.md \
  /home/louis-burroughs/IdeaProjects/durion/domains/security/docs/PERMISSION_REGISTRY.md \
  /home/louis-burroughs/IdeaProjects/durion/domains/security/docs/BASELINE_PERMISSIONS.md \
  /home/louis-burroughs/IdeaProjects/durion/domains/security/docs/RBAC_POLICY.md \
  pos-security-service/docs/AUTH_TOKEN_USAGE_GUIDE.md \
  pos-security-service/docs/security-service-guide.md \
  pos-api-gateway/docs/permissions-encoding.md \
  scripts/check-authz-doc-drift.sh \
  scripts/README.md
git commit -m "docs: establish authoritative authorization model"
```

