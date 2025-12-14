# Agent Collaboration Framework

This document describes how the various AI agents work together in this repository to ensure high-quality, maintainable code.

## Agent Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                      architecture_agent                          │
│         (Chief Architect - Design & Domain Boundaries)           │
└────────────────────────────┬────────────────────────────────────┘
                             │
                    ┌────────┴────────┐
                    │   Provides:     │
                    │   - Designs     │
                    │   - Boundaries  │
                    │   - Patterns    │
                    └────────┬────────┘
                             │
                             ▼
         ┌───────────────────────────────────────────────┐
         │         moqui_developer_agent                 │
         │    (Implementation - Turns Design into Code)  │
         └───────┬───────────────────────────┬───────────┘
                 │                           │
        ┌────────┴────────┐         ┌───────┴────────┐
        │  Consults:      │         │  Produces:     │
        │  - dba_agent    │         │  - Entities    │
        │  - sre_agent    │         │  - Services    │
        │  - dev_deploy   │         │  - Screens     │
        └─────────────────┘         │  - APIs        │
                                    │  - Vue UI      │
                                    └────────┬───────┘
                                             │
                    ┌────────────────────────┴────────────────────────┐
                    │              Submits to:                        │
                    │                                                 │
         ┌──────────▼───────┐  ┌──────────▼───────┐  ┌─────────▼────────┐
         │   test_agent     │  │   lint_agent     │  │   api_agent      │
         │  (QA Testing)    │  │  (Code Quality)  │  │  (API Contracts) │
         └──────────┬───────┘  └──────────┬───────┘  └─────────┬────────┘
                    │                     │                     │
                    └─────────────────────┴─────────────────────┘
                                          │
                                          ▼
                            ┌─────────────────────────┐
                            │  Validation Results     │
                            │  ← Fix & Resubmit →    │
                            │  moqui_developer_agent  │
                            └─────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────────────────────┐
│                          Supporting Infrastructure Agents                                  │
├────────────────┬──────────────────┬────────────────┬──────────────┬───────────────────────┤
│   dba_agent    │   sre_agent      │ dev_deploy_ag  │  docs_agent  │  typescript_agent     │
│  (Schema/DB)   │ (Observability)  │ (Docker/Deplo) │(Documentation)│  (Type Safety)        │
└────────────────┴──────────────────┴────────────────┴──────────────┴───────────────────────┘

┌──────────────────────────────────────────────────────────────────────────────────────────┐
│                            Frontend & UI Agents                                           │
├─────────────────────┬──────────────────────┬──────────────────┬──────────────────────────┤
│   quasar_agent      │   vue_agent          │  i18n_agent      │  XX-YY-language-agent    │
│ (Quasar Components) │ (Vue.js 2 Framework) │ (Internationalization) │ (Localization)   │
└─────────────────────┴──────────────────────┴──────────────────┴──────────────────────────┘
```

## Agent Roles & Responsibilities

### Core Agents

#### `architecture_agent` - Chief Architect
**Role:** Define and enforce architectural boundaries, patterns, and domain structure

**Responsibilities:**
- Design system architecture and domain boundaries
- Enforce layering (UI → Services → Entities → Integration)
- Approve all architectural changes
- Maintain RACI matrices and component dependencies
- Review implementations for architectural compliance

**Authority:** Highest - all agents must consult before making architectural decisions

---

#### `moqui_developer_agent` - Implementation Expert
**Role:** Turn approved designs into working Moqui code

**Responsibilities:**
- Implement entities, services, screens, APIs, Vue components
- Follow architectural designs from `architecture_agent`
- Instrument code with metrics per `sre_agent` guidelines
- Consult `dba_agent` before schema changes
- Submit all code to validation agents
- Resolve failures before completion

**Authority:** Implementation decisions within approved design boundaries

---

### Validation Agents

#### `test_agent` - QA Engineer
**Role:** Ensure code quality through comprehensive testing

**Responsibilities:**
- Generate unit, integration, and contract tests
- Execute test suites and analyze results
- Report failures to `moqui_developer_agent`
- Document coverage gaps
- Validate observability instrumentation

**Authority:** Can block code completion until tests pass

---

#### `lint_agent` - Code Quality Engineer
**Role:** Enforce style and static analysis standards

**Responsibilities:**
- Run Checkstyle, CodeNarc, XML validation
- Enforce formatting and naming conventions
- Report violations to `moqui_developer_agent`
- Maintain linting configuration

**Authority:** Can block code completion until violations are resolved

---

#### `api_agent` - API Architect
**Role:** Design and validate REST APIs and contracts

**Responsibilities:**
- Design REST endpoint structures
- Implement error handling and validation
- Validate API contracts and integration compliance
- Document API specifications
- Test error scenarios

**Authority:** Can block API changes until contracts are validated

---

### Infrastructure Agents

#### `dba_agent` - Database Administrator
**Role:** Ensure optimal database design and performance

**Responsibilities:**
- Design entity schemas and indexes
- Provide performance tuning recommendations
- Review all schema changes before approval
- Monitor database security vulnerabilities
- Optimize queries in services

**Authority:** Must approve all schema changes

---

#### `sre_agent` - Site Reliability Engineer
**Role:** Ensure observability and operational excellence

**Responsibilities:**
- Define functional and RED metrics
- Guide instrumentation with OpenTelemetry
- Configure metrics export to Grafana
- Document all metrics in METRICS.md
- Ensure zero business logic impact

**Authority:** Must approve observability patterns

---

#### `dev_deploy_agent` - DevOps Engineer
**Role:** Manage containerization and local deployment

**Responsibilities:**
- Build Docker images and compose configurations
- Deploy applications to dev environments
- Manage secrets and security
- Set up database and infrastructure containers
- Support test environments

**Authority:** Controls deployment pipeline and infrastructure

---

#### `docs_agent` - Technical Writer
**Role:** Create and maintain comprehensive documentation

**Responsibilities:**
- Document services, entities, and screens
- Create component documentation
- Write API documentation
- Document architectural decisions
- Create METRICS.md files

**Authority:** Can request clarification but cannot block implementation

---

### Frontend & Internationalization Agents

#### `typescript_agent` - TypeScript Expert
**Role:** Ensure type safety and maintainability in Vue.js 2 applications

**Responsibilities:**
- Design type-safe component structures
- Create TypeScript interfaces for data models
- Enforce strict TypeScript configuration
- Provide type definitions for services and APIs
- Review component type annotations
- Ensure Moqui integration types are properly defined

**Authority:** Can request type improvements but cannot block implementation

---

#### `i18n_agent` - Internationalization Expert
**Role:** Implement multi-language support and localization strategies

**Responsibilities:**
- Design translation message structure and organization
- Configure Vue-i18n for the application
- Manage locale-specific formatting (dates, numbers, currency)
- Coordinate with language-specific agents
- Implement language switching and persistence
- Validate translation completeness across locales
- Ensure accessibility in all supported languages

**Authority:** Must approve all i18n implementation and language agent coordination

---

#### `quasar_agent` - Quasar Framework Expert
**Role:** Design and implement Quasar-based user interfaces

**Responsibilities:**
- Design component layouts using Quasar components
- Implement responsive design with Quasar's breakpoints
- Apply Michelin Design System styling
- Create form components with validation
- Build data tables and dialogs
- Ensure accessibility and RTL support readiness

**Authority:** Must approve all UI component implementations

---

#### `vue_agent` - Vue.js 2 Expert
**Role:** Develop Vue.js 2 components and handle application state

**Responsibilities:**
- Design component architecture and composition
- Implement Vue components with proper reactivity
- Manage component state and watchers
- Handle data fetching and API integration
- Implement event handling and validation
- Ensure performance optimization

**Authority:** Must approve component design and implementation

---

#### `XX-YY-language_agent` - Language Specialist
**Role:** Provide native speaker review and cultural adaptation for specific languages

**Responsibilities:**
- Review translations for accuracy and native fluency
- Ensure cultural appropriateness of terminology
- Validate pluralization rules for the language
- Review date/number/currency formatting for locale
- Provide feedback on RTL (for Arabic, Hebrew, Urdu)
- Validate accessibility in translated content

**Authority:** Must approve translations for their language

**Examples:**
- `en-US-language-agent` - English (United States)
- `es-ES-language-agent` - Spanish (Spain)
- `fr-FR-language-agent` - French (France)
- `de-DE-language-agent` - German (Germany)
- `pt-BR-language-agent` - Portuguese (Brazil)
- `ja-JP-language-agent` - Japanese

---

## Collaboration Workflows

### Feature Implementation Workflow

```
1. architecture_agent → Design approved
                    ↓
2. moqui_developer_agent → Consult dba_agent (schema)
                         → Consult sre_agent (metrics)
                         → Implement code
                    ↓
3. moqui_developer_agent → Submit to validation agents
                    ↓
4. test_agent → Run tests → Report results
   lint_agent → Run linters → Report violations
   api_agent → Validate contracts → Report issues
                    ↓
5. moqui_developer_agent → Fix all failures
                         → Resubmit to validation
                    ↓
6. architecture_agent → Final structural approval
                    ↓
7. docs_agent → Document implementation
```

### Schema Change Workflow

```
1. architecture_agent → Approve entity ownership
                    ↓
2. dba_agent → Design schema and indexes
             → Approve schema structure
                    ↓
3. moqui_developer_agent → Implement entity definition
                         → Add instrumentation
                    ↓
4. test_agent → Validate entity tests
   lint_agent → Validate XML formatting
                    ↓
5. dev_deploy_agent → Deploy with migrations
                    ↓
6. docs_agent → Document entity and relationships
```

### API Development Workflow

```
1. architecture_agent → Approve API boundary
                    ↓
2. api_agent → Design endpoint structure
             → Define error handling
                    ↓
3. moqui_developer_agent → Implement endpoint
                         → Add metrics (sre_agent)
                         → Optimize queries (dba_agent)
                    ↓
4. test_agent → API contract tests
   api_agent → Validate contracts
   lint_agent → Code quality checks
                    ↓
5. docs_agent → Document API endpoints
```

### UI Component Development Workflow

```
1. architecture_agent → Approve component design and scope
                    ↓
2. quasar_agent → Design component layout
   vue_agent → Design component structure
                    ↓
3. typescript_agent → Define component types and interfaces
                   ↓
4. vue_agent → Implement Vue component
   quasar_agent → Verify Quasar components usage
                    ↓
5. test_agent → Component unit tests
   lint_agent → Code quality checks
                    ↓
6. docs_agent → Document component API and usage
```

### Internationalization (i18n) Workflow

```
1. architecture_agent → Approve i18n scope and supported locales
                    ↓
2. i18n_agent → Design translation structure
              → Configure Vue-i18n
              → Define message organization
                    ↓
3. moqui_developer_agent → Implement $t() calls in components
                         → Configure locale switching
                    ↓
4. quasar_agent → Verify UI respects locale (RTL, formatting)
   typescript_agent → Create type-safe translation types
                    ↓
5. i18n_agent → Validate translation completeness
             ↓
6. XX-YY-language_agent (per locale) → Review translations
                                     → Validate cultural appropriateness
                                     → Approve for release
                    ↓
7. docs_agent → Document supported locales and translation keys
```

## Agent Communication Patterns

### Request-Response
- `moqui_developer_agent` → `dba_agent`: "Can I create this entity?"
- `dba_agent` → `moqui_developer_agent`: "Yes, with these indexes..."
- `vue_agent` → `typescript_agent`: "What types should this component use?"
- `typescript_agent` → `vue_agent`: "Use this interface for the data..."

### Consultation
- `moqui_developer_agent` consults `sre_agent` before instrumenting code
- `api_agent` consults `architecture_agent` before defining boundaries
- `vue_agent` → `i18n_agent`: "How should I structure translation keys?"
- `quasar_agent` → `i18n_agent`: "How do I handle RTL in this component?"

### Translation Review
- `i18n_agent` → `XX-YY-language_agent`: "Please review these translations"
- `XX-YY-language_agent` → `i18n_agent`: "Approved with these suggestions..."
- `XX-YY-language_agent` → `quasar_agent`: "This text breaks the layout in RTL"

### Validation
- `moqui_developer_agent` submits to `test_agent` → receives pass/fail
- `moqui_developer_agent` submits to `lint_agent` → receives violations
- `i18n_agent` submits translations to `XX-YY-language_agent` → receives approval/feedback

### Approval
- `architecture_agent` approves final implementation
- `dba_agent` approves schema changes
- `i18n_agent` approves translation completeness
- `XX-YY-language_agent` approves translations for their language

## Key Principles

1. **Architecture First**: All work starts with `architecture_agent` approval
2. **Implementation Focus**: `moqui_developer_agent` is the execution engine
3. **Quality Gates**: Validation agents must pass before completion
4. **Domain Boundaries**: Never cross without `architecture_agent` approval
5. **Schema Control**: All schema changes through `dba_agent`
6. **Observability**: All business logic instrumented per `sre_agent`
7. **Documentation**: All implementations documented by `docs_agent`

## Escalation Paths

### Conflicting Requirements
**Escalate to:** `architecture_agent`
**Example:** DBA wants denormalization, but it violates domain boundary

### Performance vs. Design Trade-offs
**Escalate to:** `architecture_agent` + `dba_agent` + `sre_agent`
**Example:** Query optimization requires cross-domain entity access

### API Contract Changes
**Escalate to:** `architecture_agent` + `api_agent`
**Example:** Breaking change needed for business requirement

### Security Concerns
**Escalate to:** `architecture_agent` + `dev_deploy_agent`
**Example:** New dependency with security implications

## Success Criteria

Code is complete when:
- ✅ `architecture_agent` approved design
- ✅ `moqui_developer_agent` implemented code
- ✅ `dba_agent` approved schema (if applicable)
- ✅ `sre_agent` verified instrumentation
- ✅ `test_agent` all tests passing
- ✅ `lint_agent` no violations
- ✅ `api_agent` contracts validated (if applicable)
- ✅ `dev_deploy_agent` deployed successfully
- ✅ `docs_agent` documentation complete

## Agent Invocation

To invoke a specific agent in your prompts, reference them by name:

```
@architecture_agent Please review this domain boundary decision...
@moqui_developer_agent Implement this service following the approved design...
@test_agent Run comprehensive tests on the order processing module...
@dba_agent Review this entity definition for performance...
@sre_agent How should I instrument this payment processing service?
@typescript_agent Design types for the product management component...
@vue_agent Implement the product form component...
@quasar_agent Build a data table for displaying products...
@i18n_agent How should I structure translations for this feature?
@en-US-language_agent Please review these English translations...
@es-ES-language_agent ¿Por favor revisa estas traducciones al español?
@fr-FR-language_agent Veuillez vérifier ces traductions en français...
```

## UI Development Example: Multi-Language Product Management Component

### Step 1: Architecture & Design
```
@architecture_agent Please approve this component scope:
- Product listing and search
- Create/edit product dialog
- Multi-language support (EN, ES, FR)
- Responsive design for mobile/tablet/desktop
```

### Step 2: UI Layout & Components
```
@quasar_agent Design the layout for:
- Data table showing products with pagination
- Toolbar with search and create button
- Edit product dialog with form fields
- Language selector in header
```

### Step 3: Component Structure
```
@vue_agent Implement the ProductManagement.vue component with:
- Data fetching from /products endpoint
- Search and filtering logic
- Dialog state management
- Language switching with localStorage persistence
```

### Step 4: Type Safety
```
@typescript_agent Define TypeScript interfaces for:
- Product data model
- API request/response types
- Component prop types
- Translation message schema
```

### Step 5: Internationalization
```
@i18n_agent Set up translations for:
- Common UI strings (Yes, No, Save, Cancel)
- Product-specific labels (Name, Price, Category)
- Validation messages
- Date and number formatting
```

### Step 6: Language Review
```
@en-US-language_agent Review English translations
@es-ES-language_agent Revisa traducciones al español
@fr-FR-language_agent Revise traductions en français
```

### Result
Complete, multi-language, type-safe product management component!
