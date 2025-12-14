Below is a **Moqui-specific RACI matrix** tailored to your 6-month prototype plan, your domain groupings, your 2-layer service architecture, and the agentic-AI capabilities we outlined.

This matrix explicitly shows **who is Responsible (R), Accountable (A), Consulted (C), and Informed (I)** across:

* **Humans**

  * Platform Team
  * Domain Teams (Work Execution, Inventory, Product/Pricing, CRM, Accounting)
  * UI/Web + Mobile Team
  * Conversational/MCP Team
  * Product Owner / Architect

* **AI Agents**

  * Domain Clarification Agent
  * Code Scaffolding Agent
  * Integration/Regression Agent
  * Conversational Flow Agent
  * Risk Monitoring Agent

* **Moqui Components**

  * Moqui Entity Engine
  * Moqui Service Engine
  * Moqui Screen Framework
  * Moqui Security / Tenancy
  * Custom Moqui Components (Durion/TIOTF domain modules)

The matrix is organized around **activities that matter for delivery speed and cross-domain alignment**.

---

# 🚦 **Moqui-Specific RACI Matrix**

## Legend

* **A = Accountable** (final decision / owns the outcome)
* **R = Responsible** (does the work)
* **C = Consulted** (provides input)
* **I = Informed** (kept aware)

---

# 1. **Domain Modeling & Entity Design**

| Activity                      | Platform Team | Domain Teams | Product Owner | Architect | Domain Clarif. Agent | Moqui Entity Engine |
| ----------------------------- | ------------- | ------------ | ------------- | --------- | -------------------- | ------------------- |
| Define domain boundaries      | C             | R            | C             | **A**     | C                    | I                   |
| Define entities               | C             | **R**        | C             | A         | **R**                | I                   |
| Validate entity relationships | C             | R            | I             | A         | **R**                | I                   |
| Generate entity XML           | I             | R            | I             | C         | **R**                | —                   |
| Enforce naming conventions    | C             | R            | C             | **A**     | R                    | I                   |
| Detect modeling errors        | I             | I            | I             | C         | **R**                | I                   |

---

# 2. **Base Services (Moqui Service Engine)**

*State transitions, validations, CRUD, domain logic*

| Activity                          | Platform Team | Domain Teams | Architect | Code Scaffold Agent | Integration Agent | Moqui Service Engine |
| --------------------------------- | ------------- | ------------ | --------- | ------------------- | ----------------- | -------------------- |
| Define service contracts          | C             | **R**        | A         | R                   | C                 | I                    |
| Implement CRUD services           | I             | **R**        | C         | R                   | I                 | —                    |
| Implement business logic services | I             | **R**        | C         | R                   | I                 | —                    |
| Implement state transitions       | I             | **R**        | C         | R                   | I                 | —                    |
| Auto-generate boilerplate         | I             | C            | C         | **R**               | I                 | —                    |
| Validate service consistency      | I             | I            | I         | R                   | **R**             | —                    |
| Bind services to Moqui engine     | C             | R            | C         | R                   | I                 | **A**                |

---

# 3. **Experience Layer (BFF / Orchestration)**

*DTOs, orchestration, stable APIs for UI + mobile + MCP*

| Activity                                 | Platform Team | Domain Teams | Architect | Code Scaffold Agent | Integration Agent | Conversational Agent |
| ---------------------------------------- | ------------- | ------------ | --------- | ------------------- | ----------------- | -------------------- |
| Define experience API schema             | C             | **R**        | **A**     | R                   | C                 | C                    |
| Generate DTOs                            | I             | R            | C         | **R**               | I                 | C                    |
| Implement orchestration logic            | I             | **R**        | C         | R                   | I                 | C                    |
| Map Moqui services → experience layer    | I             | **R**        | C         | R                   | I                 | —                    |
| Ensure experience layer alignment w/ UI  | C             | R            | C         | I                   | **R**             | —                    |
| Ensure experience layer alignment w/ MCP | I             | C            | C         | I                   | R                 | **A**                |

---

# 4. **UI / Web Layer (Service Writer)**

| Activity                     | UI Team | Platform | Domain Teams | Code Scaffold Agent | Product Owner | Architect |
| ---------------------------- | ------- | -------- | ------------ | ------------------- | ------------- | --------- |
| UI component design          | **R**   | I        | C            | C                   | A             | C         |
| Generate UI scaffolding      | C       | I        | I            | **R**               | I             | C         |
| Integrate w/ experience APIs | **R**   | I        | C            | C                   | C             | C         |
| UX refinement                | **R**   | I        | I            | C                   | A             | C         |

---

# 5. **Mobile UI (Mechanic)**

| Activity                       | Mobile Team | Experience Layer Team | Scaffolding Agent | Product Owner | Architect |
| ------------------------------ | ----------- | --------------------- | ----------------- | ------------- | --------- |
| Screen design                  | **R**       | I                     | C                 | A             | C         |
| Integrate with experience APIs | **R**       | C                     | C                 | C             | C         |
| Add camera/photo handling      | **R**       | I                     | I                 | I             | C         |
| Validate UX flows              | R           | I                     | C                 | **A**         | C         |

---

# 6. **Conversational / MCP Integration**

| Activity                        | Conversational Team | Experience Team | Conversational Agent | Domain Team | Architect |
| ------------------------------- | ------------------- | --------------- | -------------------- | ----------- | --------- |
| Define MCP tools                | R                   | C               | **R**                | C           | A         |
| Map tools → experience services | R                   | **R**           | C                    | C           | A         |
| Validate conversation flows     | C                   | I               | **R**                | C           | A         |
| Maintain safety, guardrails     | I                   | I               | R                    | C           | **A**     |
| Generate conversational tests   | I                   | I               | **R**                | C           | C         |

---

# 7. **Cross-Domain Integration & Testing**

| Activity                           | Platform Team | Domain Teams | Integration Agent | Risk Agent | Architect |
| ---------------------------------- | ------------- | ------------ | ----------------- | ---------- | --------- |
| API regression testing             | I             | C            | **R**             | I          | C         |
| Cross-domain flow validation       | C             | R            | **R**             | I          | A         |
| Detect DTO drift                   | I             | I            | **R**             | I          | C         |
| Detect entity/service drift        | I             | R            | **R**             | I          | A         |
| Detect scope creep                 | I             | I            | C                 | **R**      | A         |
| Integration failure prioritization | C             | C            | R                 | **A**      | A         |

---

# 8. **Security / Multi-Tenancy**

| Activity                      | Platform Team | Domain Teams | Architect | Moqui Security/Tenancy | Risk Agent |
| ----------------------------- | ------------- | ------------ | --------- | ---------------------- | ---------- |
| Configure authentication      | **R**         | I            | A         | C                      | I          |
| Configure tenant boundaries   | **R**         | I            | A         | C                      | I          |
| Apply permissions to services | C             | **R**        | A         | C                      | I          |
| Validate security coverage    | C             | R            | A         | I                      | **R**      |
| Enforce Moqui best practices  | **R**         | C            | C         | A                      | I          |

---

# 9. **DevOps / Environment Setup**

| Activity                 | Platform Team | Domain Teams | AI Agents | Architect |
| ------------------------ | ------------- | ------------ | --------- | --------- |
| CI/CD pipeline setup     | **R**         | I            | C         | A         |
| Automated test execution | I             | I            | **R**     | C         |
| Environment provisioning | **R**         | I            | I         | A         |
| Regression monitoring    | C             | I            | **R**     | A         |

---

# 10. **Governance, Architecture, and Release**

| Activity                  | Product Owner | Architect | Platform Team | Domain Teams | Risk Agent |
| ------------------------- | ------------- | --------- | ------------- | ------------ | ---------- |
| Approve scope & changes   | **A**         | C         | I             | I            | C          |
| Approve architecture      | C             | **A**     | R             | C            | I          |
| Enforce domain boundaries | C             | **A**     | R             | R            | I          |
| Release readiness         | A             | C         | R             | R            | C          |
| Produce risk alerts       | I             | I         | I             | I            | **R**      |

---

# 🔥 Key Takeaways

### 1. Moqui centralizes *base service responsibilities*,

but domain teams still **own business logic and entity definitions**.

### 2. Experience layer remains the **true integration boundary**,

and is mostly owned by vertical domain squads.

### 3. Agentic AI plays three critical roles:

* **Clarify** domain rules before they reach Moqui (prevent schema mistakes).
* **Generate** consistent scaffolding for Moqui entities/services + experience DTOs.
* **Test** integration boundaries, preventing month-5 surprises.

### 4. The **Architect + Platform Team** remain accountable for consistency,

but domain teams are responsible for delivery.

### 5. MCP is **fully dependent** on the experience layer,

and the conversational agent ensures alignment remains intact.

---