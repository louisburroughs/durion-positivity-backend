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
  INVOC -->|API| TAX
  INVOC -->|API| DOC
  WO -->|API| TAX
  WO -->|API| DOC
  CAT -->|API| PRICE
  PPLC -->|API| SEC

  WAR -->|API v1 scoped exception| INVOC
  WAR -->|API v1 scoped exception| WO
  WAR -->|API v1 scoped exception| CAT
  WAR -->|API v1 scoped exception| CUS
  WAR -->|API v1 scoped exception| VEHINV

  %% Implemented event connectors (representative, code-evidenced)
  WO ==>|workorder.events.v1| CUS
  WO ==>|workorder.events.v1| PPL

  PPLC ==>|people-contact.events.v1| PPL
  PPLC ==>|people-contact.events.v1| SHOP
  PPLC ==>|people-contact.events.v1| WO

  PPL ==>|people.events.v1| SHOP
  PPL ==>|people.events.v1| WO

  CUS ==>|customer.events.v1| SHOP
  CUS ==>|customer.events.v1| WO

  LOC ==>|location.events.v1| PPL
  LOC ==>|location.events.v1| WO

  INV ==>|inventory.events.v1| CAT
  INV ==>|inventory.events.v1| WO

  VEHINV ==>|vehicle.events.v1| SHOP

  %% Planned / in-progress edges from ADR-0044 and coupling assessment
  INVOC -.->|Planned: replace remaining customer lookup via events| CUS
  INVOC -.->|Planned: replace remaining location lookup via events| LOC
  WO -.->|Planned: remove remaining shop-manager sync context call| SHOP
  LOC -.->|Planned: complete location event mirrors for all consumers| INV
  ACC -.->|Planned: command/result event loops with invoice/workorder| INVOC
  ACC -.->|Planned: command/result event loops with invoice/workorder| WO

  %% External provider context
  ACC -->|API| STRIPE
  VCAR -->|API| CARAPI
  VNHT -->|API| NHTSA
  TAX -->|API| ETAX
  MCP -->|API| EXA
  MCP -->|API| OLLAMA

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
  CUS ==>|customer.events.v1| INVOC
  CUS ==>|customer.events.v1| WO
  CUS ==>|customer.events.v1| SHOP

  LOC ==>|location.events.v1| INV
  LOC ==>|location.events.v1| PPL
  LOC ==>|location.events.v1| INVOC
  LOC ==>|location.events.v1| WO

  PPLC ==>|people-contact.events.v1| PPL
  PPLC ==>|people-contact.events.v1| SHOP
  PPLC ==>|people-contact.events.v1| WO

  PPL ==>|people.events.v1| SHOP
  PPL ==>|people.events.v1| WO

  WO ==>|workorder.events.v1| CUS
  WO ==>|workorder.events.v1| PPL
  WO ==>|workorder.events.v1| ACC

  INV ==>|inventory.events.v1| CAT
  INV ==>|inventory.events.v1| WO

  INVOC ==>|invoice.events.v1| ACC
  INVOC ==>|invoice.events.v1| WO

  VEHINV ==>|vehicle.events.v1| CUS
  VEHINV ==>|vehicle.events.v1| SHOP
  VEHINV ==>|vehicle.events.v1| WO

  %% Command channels for cross-domain writes
  ACC ==>|invoice.commands.v1| INVOC
  ACC ==>|workorder.commands.v1| WO
  WO ==>|inventory.commands.v1| INV
  PPLC ==>|security linkage commands/events| SEC

  %% Utility sync calls remain allowed
  INVOC -->|API| TAX
  INVOC -->|API| DOC
  WO -->|API| TAX
  WO -->|API| DOC
  CAT -->|API| PRICE

  %% Planned dotted edges (remaining migration intent)
  WAR -.->|Planned v2: retire scoped sync exception| INVOC
  WAR -.->|Planned v2: retire scoped sync exception| WO
  WAR -.->|Planned v2: retire scoped sync exception| CUS
  WAR -.->|Planned v2: retire scoped sync exception| CAT
  WAR -.->|Planned v2: retire scoped sync exception| VEHINV

  %% External providers
  ACC -->|API| STRIPE
  TAX -->|API| ETAX
  VEHINV -->|API| CARAPI
  VEHINV -->|API| NHTSA

  BLOCK["Policy: Domain-to-domain synchronous REST is forbidden (ADR-0044), except scoped pos-warranty v1 exception"]
  class BLOCK warn
  BLOCK --- WAR
```

## Notes and Caveats

- `pos-agent-framework` exists as a repo directory but is not currently declared in parent `pom.xml` modules.
- Event edges shown as implemented are based on listener/publisher/topic references in source classes and configuration defaults.
- Planned dotted edges represent ADR/assessment intent and in-flight migration targets; they are intentionally shown separately from implemented solid connectors.
