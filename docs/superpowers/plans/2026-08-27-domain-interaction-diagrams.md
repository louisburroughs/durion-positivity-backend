# Domain Interaction Diagrams Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve the 2026-07-16 domain interaction model and publish an
exhaustive, source-evidenced current model at the canonical document path.

**Architecture:** Normalize current source/configuration evidence into one edge
catalog, then project that catalog into three Mermaid diagrams organized by
transport: synchronous calls, domain facts/replicas, and commands/results. ADR-0044
and `DomainWallsTest` constrain synchronous domain edges but never substitute for
implementation evidence.

**Tech Stack:** Markdown, Mermaid, ripgrep, Maven, ArchUnit, Git

---

## Task 1: Preserve the historical model

**Files:**

- Rename: `docs/domain-interaction-diagrams.md` to
  `docs/domain-interaction-diagrams-2026-07-16.md`
- Modify: `docs/domain-interaction-diagrams-2026-07-16.md`

- [ ] **Step 1: Confirm the canonical document is unchanged and tracked**

Run:

```bash
git status --short -- docs/domain-interaction-diagrams.md
git log -1 --format='%h %ad %s' --date=short -- \
  docs/domain-interaction-diagrams.md
```

Expected: no status entry and latest historical commit dated 2026-07-16.

- [ ] **Step 2: Rename the file without rewriting its historical body**

Use the workspace file-editing tool to move:

```text
docs/domain-interaction-diagrams.md
→ docs/domain-interaction-diagrams-2026-07-16.md
```

- [ ] **Step 3: Add a historical-version notice**

Insert directly below the title:

```markdown
> **Historical version:** Evidence snapshot from 2026-07-16. This document is
> superseded by the [current domain interaction diagrams](domain-interaction-diagrams.md).
```

- [ ] **Step 4: Verify Git detects a rename with only the notice added**

Run:

```bash
git diff --summary -- \
  docs/domain-interaction-diagrams.md \
  docs/domain-interaction-diagrams-2026-07-16.md
git diff --check -- docs/domain-interaction-diagrams-2026-07-16.md
```

Expected: a rename plus the notice; no whitespace errors.

## Task 2: Build the current communication-edge inventory

**Files:**

- Read: `pom.xml`
- Read: `pos-archunit/src/test/java/com/positivity/archunit/DomainWallsTest.java`
- Read: `pos-*/src/main/java/**/*.java`
- Read: `pos-*/src/main/resources/**/*.{yml,yaml,properties}`

- [ ] **Step 1: Inventory deployed modules**

Run:

```bash
rg -n '<module>pos-[^<]+</module>' pom.xml
```

Expected: the authoritative reactor module list used to classify nodes.

- [ ] **Step 2: Inventory synchronous service and provider targets**

Run:

```bash
rg -n \
  'http://pos-|lb://|baseUrl\(|service-id|serviceId|@FeignClient|@HttpExchange' \
  pos-*/src/main/java pos-*/src/main/resources \
  -g '*.java' -g '*.yml' -g '*.yaml' -g '*.properties'
```

Expected: concrete callers and configured targets for synchronous edges.

- [ ] **Step 3: Inventory Kafka consumers and producers**

Run:

```bash
rg -n \
  '@KafkaListener|KafkaTemplate|\.send\(|topic|commands\.v[0-9]+|events\.v[0-9]+|manifest\.v[0-9]+' \
  pos-*/src/main/java pos-*/src/main/resources \
  -g '*.java' -g '*.yml' -g '*.yaml' -g '*.properties'
```

Expected: topic ownership, producers, consumers, command/result paths, manifest
flows, and configured DLQs.

- [ ] **Step 4: Reconcile domain synchronous edges with enforcement**

Run:

```bash
rg -n -A35 \
  'SCOPED_MODULE_EXCEPTIONS|SCOPED_SOURCE_EXCEPTIONS' \
  pos-archunit/src/test/java/com/positivity/archunit/DomainWallsTest.java
```

Expected: permanent `pos-warranty` to `pos-invoice`, `pos-order` to
`pos-invoice`, and class-scoped catalog/order supplier-stock exceptions.

- [ ] **Step 5: Reject non-runtime and unproven edges**

Exclude shared-library imports, tests, mocks, audit-only `@EmitEvent` transport,
comment-only topic references, and ADR-planned edges without matching runtime
source evidence. Record configuration-dependent ambiguity as a caveat.

## Task 3: Publish the current transport-layer model

**Files:**

- Create: `docs/domain-interaction-diagrams.md`

- [ ] **Step 1: Add document metadata and supersession notice**

Create the canonical document with:

```markdown
# Backend Domain Interaction Diagrams

> **Current version:** Source evidence captured 2026-08-27. This document
> supersedes the [2026-07-16 model](domain-interaction-diagrams-2026-07-16.md).
```

Then define current-only scope, evidence hierarchy, and a legend. Explicitly state
that absent edges are not claims of architectural impossibility and that ADR policy
does not prove runtime implementation.

- [ ] **Step 2: Add the synchronous communication diagram**

Create a Mermaid `flowchart LR` containing all proven synchronous module and
external-provider calls. Style utility, domain, infrastructure, and external nodes
distinctly. Mark each implemented cross-domain exception with stable IDs `S01`,
`S02`, and so on and cite the applicable ADR-0044 amendment in the edge catalog.

- [ ] **Step 3: Add the domain facts and replicas diagram**

Create a Mermaid `flowchart LR` showing every proven fact/event producer-consumer
edge. Label connectors with stable IDs `E01`, `E02`, and so on plus exact topic
names. Include manifest and reconciliation flows only when both endpoints are
proven in current source.

- [ ] **Step 4: Add the commands and results diagram**

Create a Mermaid `flowchart LR` showing every proven command producer-consumer and
result-event return edge. Label connectors with stable IDs `C01`, `C02`, and so on
plus exact topics. Keep command requests visually distinct from result facts.

- [ ] **Step 5: Add the exhaustive edge catalog**

Add one table row per normalized edge with these columns:

```text
ID | Origin | Target | Transport/topic | Purpose | Evidence
```

Use repository-relative Markdown links to concrete source files. Every Mermaid
edge ID must exist in the catalog, and every catalog ID must occur in exactly one
diagram.

- [ ] **Step 6: Add caveats**

Document grouped startup registration edges, profile-conditional/configurable
topics, audit-only `@EmitEvent` exclusion, and any source ambiguity discovered in
Task 2. Do not add planned connectors.

## Task 4: Verify the superseding model

**Files:**

- Verify: `docs/domain-interaction-diagrams.md`
- Verify: `docs/domain-interaction-diagrams-2026-07-16.md`

- [ ] **Step 1: Check Markdown and diff integrity**

Run:

```bash
git diff --check -- \
  docs/domain-interaction-diagrams.md \
  docs/domain-interaction-diagrams-2026-07-16.md
rg -ni 'planned|target state|to-be|in-progress' \
  docs/domain-interaction-diagrams.md
```

Expected: no whitespace errors; references to planned/target edges occur only in
statements that explicitly exclude them.

- [ ] **Step 2: Verify relative Markdown links**

Extract repository-relative links from both files and assert each target exists.
Ignore fragment-only and HTTP(S) links.

Expected: zero missing local targets.

- [ ] **Step 3: Verify edge-catalog consistency**

Extract all `Snn`, `Enn`, and `Cnn` IDs from Mermaid blocks and the edge catalog.
Assert every diagram ID has one catalog row and every catalog ID occurs in exactly
one diagram.

Expected: zero missing or duplicate IDs.

- [ ] **Step 4: Parse Mermaid blocks when tooling is available**

Run `mmdc` against each extracted Mermaid block when `mmdc` is installed. If it is
not installed, use the repository's available Markdown/Mermaid validator and record
the unavailable renderer explicitly.

Expected: every block parses successfully or renderer unavailability is reported.

- [ ] **Step 5: Run ADR-0044 enforcement tests**

Run:

```bash
./mvnw -pl pos-archunit -am -Dtest=DomainWallsTest test
```

Expected: `BUILD SUCCESS` with zero test failures.

- [ ] **Step 6: Review final history and working tree**

Run:

```bash
git diff --stat -- \
  docs/domain-interaction-diagrams.md \
  docs/domain-interaction-diagrams-2026-07-16.md \
  docs/superpowers/specs/2026-08-27-domain-interaction-diagrams-design.md \
  docs/superpowers/plans/2026-08-27-domain-interaction-diagrams.md
git status --short
```

Expected: one historical rename, one new canonical model, one design spec, and one
implementation plan; unrelated pre-existing changes remain untouched.
