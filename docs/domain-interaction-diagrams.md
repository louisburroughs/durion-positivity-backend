# Backend Domain Interaction Diagrams

This document maps backend module interactions using both source-of-truth code evidence and ADR policy intent.

## Scope and Sources

- Module inventory from `pom.xml` (declared modules) and `pos-*` folders.
- Runtime/API wall enforcement from `pos-archunit` `DomainWallsTest`.
- Event-only policy from ADR-0044.
- Current migration context from `docs/module-coupling/issue-823-event-only-domain-walls-assessment.md`.

## Legend

- Solid arrow (`-->`): implemented synchronous API call.
- Thick arrow (`==>`): implemented event connector (Kafka / event ACL pattern).
- Dotted arrow (`-.->`): planned/in-progress edge (documented target, not fully converged in code).
- De-emphasized nodes: non-domain modules (utility, libraries, infra, external providers).

## Diagram 1: Current Implementation (Code-Evidenced + Planned Dotted)

```mermaid
flowchart LR
  classDef domain fill:#eaf3ff,stroke:#3b82f6,stroke-width:1px,color:#0f172a
  classDef nondomain fill:#f8fafc,stroke:#94a3b8,stroke-width:1px,color:#334155
  classDef external fill:#f8fafc,stroke:#94a3b8,stroke-dasharray: 4 4,color:#334155
  classDef note fill:#fff7ed,stroke:#f59e0b,stroke-width:1px,color:#7c2d12

  subgraph Domains
    ACC[pos-accounting]
    CAT[pos-catalog]
    CUS[pos-customer]
    INQ[pos-inquiry]
    INV[pos-inventory]
    INVOC[pos-invoice]
    LOC[pos-location]
    ORD[pos-order]
    PPL[pos-people]
    PPLC[pos-people-contact]
    SHOP[pos-shop-manager]
    VEHINV[pos-vehicle-inventory]
    VEHFIT[pos-vehicle-fitment]
    VCAR[pos-vehicle-reference-carapi]
    VNHT[pos-vehicle-reference-nhtsa]
    WO[pos-workorder]
    BULK[pos-bulk-loader]
    WAR[pos-warranty]
  end

  subgraph Utility_and_Infra_non_domain
    GATE[pos-api-gateway]
    SEC[pos-security-service]
    DOC[pos-documents]
    IMG[pos-image]
    TAX[pos-tax]
    PRICE[pos-price]
    EVR[pos-event-receiver]
    MCP[pos-mcp-server]
    DISC[pos-service-discovery]
  end

  subgraph Libraries_non_deployed
    DEVENTS[pos-domain-events]
    EVENTS[pos-events]
    SDTO[pos-shared-dtos]
    ARCH[pos-archunit]
  end

  subgraph External_Providers
    STRIPE[Stripe]
    CARAPI[CarAPI]
    NHTSA[NHTSA]
    ETAX[External Tax Provider]
    EXA[Exa]
    OLLAMA[Ollama]
  end

  class ACC,CAT,CUS,INQ,INV,INVOC,LOC,ORD,PPL,PPLC,SHOP,VEHINV,VEHFIT,VCAR,VNHT,WO,BULK,WAR domain
  class GATE,SEC,DOC,IMG,TAX,PRICE,EVR,MCP,DISC,DEVENTS,EVENTS,SDTO,ARCH nondomain
  class STRIPE,CARAPI,NHTSA,ETAX,EXA,OLLAMA external

  %% Implemented synchronous API edges (including ADR-0044 scoped warranty exception)
  INVOC -->|API C1| TAX
  INVOC -->|API C1| DOC
  WO -->|API C1| TAX
  WO -->|API C1| DOC
  CAT -->|API C2| PRICE
  PPLC -->|API C3| SEC

  WAR -->|API C4 scoped v1| INVOC
  WAR -->|API C4 scoped v1| WO
  WAR -->|API C4 scoped v1| CAT
  WAR -->|API C4 scoped v1| CUS
  WAR -->|API C4 scoped v1| VEHINV

  %% Implemented event connectors (representative, code-evidenced)
  WO ==>|workorder.events.v1 C5| CUS
  WO ==>|workorder.events.v1 C5| PPL

  PPLC ==>|people-contact.events.v1 C5| PPL
  PPLC ==>|people-contact.events.v1 C5| SHOP
  PPLC ==>|people-contact.events.v1 C5| WO

  PPL ==>|people.events.v1 C5| SHOP
  PPL ==>|people.events.v1 C5| WO

  CUS ==>|customer.events.v1 C5| SHOP
  CUS ==>|customer.events.v1 C5| WO

  LOC ==>|location.events.v1 C5| PPL
  LOC ==>|location.events.v1 C5| WO

  INV ==>|inventory.events.v1 C5| CAT
  INV ==>|inventory.events.v1 C5| WO

  VEHINV ==>|vehicle.events.v1 C5| SHOP

  %% Planned / in-progress edges from ADR-0044 and coupling assessment
  INVOC -.->|Planned: replace remaining customer lookup via events S1| CUS
  INVOC -.->|Planned: replace remaining location lookup via events S2| LOC
  WO -.->|Planned: remove remaining shop-manager sync context call S3| SHOP
  LOC -.->|Planned: complete location event mirrors for all consumers S4| INV
  ACC -.->|Planned: command/result event loops with invoice/workorder S5| INVOC
  ACC -.->|Planned: command/result event loops with invoice/workorder S5| WO

  %% External provider context
  ACC -->|API C6| STRIPE
  VCAR -->|API C6| CARAPI
  VNHT -->|API C6| NHTSA
  TAX -->|API C6| ETAX
  MCP -->|API C6| EXA
  MCP -->|API C6| OLLAMA

  NOTE1["DomainWallsTest enforces: no sync domain-to-domain calls except scoped pos-warranty v1 exception"]
  NOTE2["pos-event-receiver pipeline remains audit-only; domain data transport is Kafka domain topics"]
  class NOTE1,NOTE2 note
  NOTE1 --- ARCH
  NOTE2 --- EVR
```

## Diagram 2: Target Policy State (ADR-0044) + Planned Dotted

```mermaid
flowchart LR
  classDef domain fill:#eaf3ff,stroke:#3b82f6,stroke-width:1px,color:#0f172a
  classDef nondomain fill:#f8fafc,stroke:#94a3b8,stroke-width:1px,color:#334155
  classDef external fill:#f8fafc,stroke:#94a3b8,stroke-dasharray: 4 4,color:#334155
  classDef warn fill:#fff1f2,stroke:#ef4444,stroke-width:1px,color:#7f1d1d

  subgraph Domain_Modules_Event_Only_Walls
    ACC[pos-accounting]
    CAT[pos-catalog]
    CUS[pos-customer]
    INV[pos-inventory]
    INVOC[pos-invoice]
    LOC[pos-location]
    PPL[pos-people]
    PPLC[pos-people-contact]
    SHOP[pos-shop-manager]
    VEHINV[pos-vehicle-inventory]
    WO[pos-workorder]
    WAR[pos-warranty]
  end

  subgraph Utility_Modules_Sync_Allowed_non_domain
    GATE[pos-api-gateway]
    SEC[pos-security-service]
    DOC[pos-documents]
    IMG[pos-image]
    TAX[pos-tax]
    PRICE[pos-price]
    EVR[pos-event-receiver]
  end

  subgraph Shared_Contract_Libraries_non_domain
    DEVENTS[pos-domain-events]
    EVENTS[pos-events]
  end

  subgraph External_Providers
    STRIPE[Stripe]
    CARAPI[CarAPI]
    NHTSA[NHTSA]
    ETAX[External Tax Provider]
  end

  class ACC,CAT,CUS,INV,INVOC,LOC,PPL,PPLC,SHOP,VEHINV,WO,WAR domain
  class GATE,SEC,DOC,IMG,TAX,PRICE,EVR,DEVENTS,EVENTS nondomain
  class STRIPE,CARAPI,NHTSA,ETAX external

  %% Policy-intended event mesh
  CUS ==>|customer.events.v1 T1| INVOC
  CUS ==>|customer.events.v1 T1| WO
  CUS ==>|customer.events.v1 T1| SHOP

  LOC ==>|location.events.v1 T1| INV
  LOC ==>|location.events.v1 T1| PPL
  LOC ==>|location.events.v1 T1| INVOC
  LOC ==>|location.events.v1 T1| WO

  PPLC ==>|people-contact.events.v1 T1| PPL
  PPLC ==>|people-contact.events.v1 T1| SHOP
  PPLC ==>|people-contact.events.v1 T1| WO

  PPL ==>|people.events.v1 T1| SHOP
  PPL ==>|people.events.v1 T1| WO

  WO ==>|workorder.events.v1 T1| CUS
  WO ==>|workorder.events.v1 T1| PPL
  WO ==>|workorder.events.v1 T1| ACC

  INV ==>|inventory.events.v1 T1| CAT
  INV ==>|inventory.events.v1 T1| WO

  INVOC ==>|invoice.events.v1 T1| ACC
  INVOC ==>|invoice.events.v1 T1| WO

  VEHINV ==>|vehicle.events.v1 T1| CUS
  VEHINV ==>|vehicle.events.v1 T1| SHOP
  VEHINV ==>|vehicle.events.v1 T1| WO

  %% Command channels for cross-domain writes
  ACC ==>|invoice.commands.v1 T2| INVOC
  ACC ==>|workorder.commands.v1 T2| WO
  WO ==>|inventory.commands.v1 T2| INV
  PPLC ==>|security linkage commands/events T2| SEC

  %% Utility sync calls remain allowed
  INVOC -->|API T3| TAX
  INVOC -->|API T3| DOC
  WO -->|API T3| TAX
  WO -->|API T3| DOC
  CAT -->|API T3| PRICE

  %% Planned dotted edges (remaining migration intent)
  WAR -.->|Planned v2: retire scoped sync exception S6| INVOC
  WAR -.->|Planned v2: retire scoped sync exception S6| WO
  WAR -.->|Planned v2: retire scoped sync exception S6| CUS
  WAR -.->|Planned v2: retire scoped sync exception S6| CAT
  WAR -.->|Planned v2: retire scoped sync exception S6| VEHINV

  %% External providers
  ACC -->|API T4| STRIPE
  TAX -->|API T4| ETAX
  VEHINV -->|API T4| CARAPI
  VEHINV -->|API T4| NHTSA

  BLOCK["Policy: Domain-to-domain synchronous REST is forbidden (ADR-0044), except scoped pos-warranty v1 exception"]
  class BLOCK warn
  BLOCK --- WAR
```

## Notes and Caveats

- `pos-agent-framework` exists as a repo directory but is not currently declared in parent `pom.xml` modules.
- Event edges shown as implemented are based on listener/publisher/topic references in source classes and configuration defaults.
- Planned dotted edges represent ADR/assessment intent and in-flight migration targets; they are intentionally shown separately from implemented solid connectors.

## Edge Source Map (All Connector Types)

- [S1] Invoice customer lookup replacement: current sync dependency and planned retirement via `customer.events.v1`.
  - Call graph edge: [issue-823-event-only-domain-walls-assessment.md#L46](module-coupling/issue-823-event-only-domain-walls-assessment.md#L46)
  - Phase 4 retirement plan: [issue-823-event-only-domain-walls-assessment.md#L181-L182](module-coupling/issue-823-event-only-domain-walls-assessment.md#L181-L182)
- [S2] Invoice location lookup replacement: current sync dependency and planned retirement via `location.events.v1`.
  - Call graph edge: [issue-823-event-only-domain-walls-assessment.md#L47](module-coupling/issue-823-event-only-domain-walls-assessment.md#L47)
  - Phase 4 retirement plan: [issue-823-event-only-domain-walls-assessment.md#L183-L185](module-coupling/issue-823-event-only-domain-walls-assessment.md#L183-L185)
- [S3] Workorder shop-manager context call removal in remaining migration phase.
  - Call graph edge: [issue-823-event-only-domain-walls-assessment.md#L68](module-coupling/issue-823-event-only-domain-walls-assessment.md#L68)
  - Phase 5 remaining edges: [issue-823-event-only-domain-walls-assessment.md#L187-L190](module-coupling/issue-823-event-only-domain-walls-assessment.md#L187-L190)
- [S4] Location event mirrors completion for inventory and other consumers.
  - Current inventory/location sync edges: [issue-823-event-only-domain-walls-assessment.md#L44](module-coupling/issue-823-event-only-domain-walls-assessment.md#L44)
  - Phase 4 location decoupling: [issue-823-event-only-domain-walls-assessment.md#L183-L185](module-coupling/issue-823-event-only-domain-walls-assessment.md#L183-L185)
- [S5] Accounting command/result event loop migration with invoice and workorder.
  - Current write/read couplings: [issue-823-event-only-domain-walls-assessment.md#L38-L39](module-coupling/issue-823-event-only-domain-walls-assessment.md#L38-L39)
  - Phase 1 command/result plan: [issue-823-event-only-domain-walls-assessment.md#L159-L160](module-coupling/issue-823-event-only-domain-walls-assessment.md#L159-L160)
- [S6] Warranty scoped sync exception is temporary and targeted for event-fed migration in v2.
  - Scoped exception and v2 evolution note: [ADR-0044 amendment §2026-07-16](https://github.com/louisburroughs/durion/blob/master/docs/adr/0044-platform-event-only-domain-walls.adr.md#2026-07-16--scoped-exception-pos-warranty-v1-synchronous-clients)
  - Warranty v2 migration target language: [ADR-0044 evolution note](https://github.com/louisburroughs/durion/blob/master/docs/adr/0044-platform-event-only-domain-walls.adr.md#L237-L238)

- [C1] Current utility sync calls from invoice/workorder to tax/documents.
  - Call graph edges: [issue-823-event-only-domain-walls-assessment.md#L50-L51](module-coupling/issue-823-event-only-domain-walls-assessment.md#L50-L51), [issue-823-event-only-domain-walls-assessment.md#L69-L70](module-coupling/issue-823-event-only-domain-walls-assessment.md#L69-L70)
- [C2] Current catalog pricing sync call.
  - Call graph edge: [issue-823-event-only-domain-walls-assessment.md#L41](module-coupling/issue-823-event-only-domain-walls-assessment.md#L41)
- [C3] Current people-contact to security linkage sync call category.
  - Utility allowlist (security-service): [0044-platform-event-only-domain-walls.adr.md#L44-L45](https://github.com/louisburroughs/durion/blob/master/docs/adr/0044-platform-event-only-domain-walls.adr.md#L44-L45)
- [C4] Current warranty scoped synchronous exception edges (v1).
  - Amendment scope: [0044-platform-event-only-domain-walls.adr.md#L223-L227](https://github.com/louisburroughs/durion/blob/master/docs/adr/0044-platform-event-only-domain-walls.adr.md#L223-L227)
- [C5] Current event connector set in Diagram 1 (code-evidenced representative set).
  - Existing async messaging baseline and precedent: [issue-823-event-only-domain-walls-assessment.md#L83-L91](module-coupling/issue-823-event-only-domain-walls-assessment.md#L83-L91)
  - Event-only direction and replicas rule: [0044-platform-event-only-domain-walls.adr.md#L65-L73](https://github.com/louisburroughs/durion/blob/master/docs/adr/0044-platform-event-only-domain-walls.adr.md#L65-L73)
- [C6] Current external provider API connectors.
  - Assessment exclusions for external API callers: [issue-823-event-only-domain-walls-assessment.md#L77-L81](module-coupling/issue-823-event-only-domain-walls-assessment.md#L77-L81)

- [T1] Target policy-state event mesh (facts on `{domain}.events.v1`).
  - Decision and rules: [0044-platform-event-only-domain-walls.adr.md#L32-L36](https://github.com/louisburroughs/durion/blob/master/docs/adr/0044-platform-event-only-domain-walls.adr.md#L32-L36), [0044-platform-event-only-domain-walls.adr.md#L65-L78](https://github.com/louisburroughs/durion/blob/master/docs/adr/0044-platform-event-only-domain-walls.adr.md#L65-L78)
- [T2] Target policy-state command channels (`{domain}.commands.v1`).
  - Write-via-command rule and topic convention: [0044-platform-event-only-domain-walls.adr.md#L70-L72](https://github.com/louisburroughs/durion/blob/master/docs/adr/0044-platform-event-only-domain-walls.adr.md#L70-L72), [0044-platform-event-only-domain-walls.adr.md#L104-L105](https://github.com/louisburroughs/durion/blob/master/docs/adr/0044-platform-event-only-domain-walls.adr.md#L104-L105)
- [T3] Target policy-state utility sync calls remain allowed.
  - Utility allowlist and R2: [0044-platform-event-only-domain-walls.adr.md#L44-L45](https://github.com/louisburroughs/durion/blob/master/docs/adr/0044-platform-event-only-domain-walls.adr.md#L44-L45), [0044-platform-event-only-domain-walls.adr.md#L60-L64](https://github.com/louisburroughs/durion/blob/master/docs/adr/0044-platform-event-only-domain-walls.adr.md#L60-L64)
- [T4] Target policy-state external provider API connectors.
  - Utility/external API posture context: [issue-823-event-only-domain-walls-assessment.md#L77-L81](module-coupling/issue-823-event-only-domain-walls-assessment.md#L77-L81)
