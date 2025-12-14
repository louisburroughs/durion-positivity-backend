---
name: lint_agent
description: Code Quality Engineer - Style enforcement and static analysis
---

## Purpose

This agent is the **Lint Agent** for this repository.

The Lint Agent is responsible for:

- Enforcing **consistent style** across the codebase.
- Catching **common defects early** via static analysis.
- Enforcing **project- and Moqui-specific conventions**.
- Running **deterministically** locally and in CI.

This document is used by:

- Developers (humans) running lint locally.
- Automation (CI jobs, bots).
- Code assistants (e.g., GitHub Copilot) to infer our conventions and preferred patterns.

---

## 2. Tech Stack in Scope

The Lint Agent covers the following technologies:

- **Java 11** (framework and component implementations)
- **Groovy** (services, scripts, and business logic)
- **XML** (Moqui DSL files):
  - Entity definitions
  - Service definitions
  - Screen definitions
  - Forms, actions, and transitions
- **JavaScript/CSS** (webroot assets and theme resources)
- **Gradle** (build configuration)
- **Moqui framework** conventions and component structure

Out of scope (for now):

- Binary artifacts
- Generated code (build/ output)
- Third-party dependencies and vendor directories
- Database schemas

---

## 3. Directories and Targets

The Lint Agent targets this project structure:

- **Framework source code**:
  - `framework/src/main/java/**`
  - `framework/src/main/groovy/**`
  - `framework/src/test/groovy/**`
- **Component modules** (PRIMARY FOCUS - most business logic lives here):
  - `runtime/component/*/src/main/java/**` (component Java code)
  - `runtime/component/*/src/main/groovy/**` (component Groovy services)
  - `runtime/component/*/screen/**/*.xml` (screen definitions)
  - `runtime/component/*/service/**/*.xml` (service definitions)
  - `runtime/component/*/entity/*.xml` (entity definitions)
  - `runtime/component/*/data/*.xml` (seed data)
  - `runtime/component/*/webroot/**` (web resources - JS, CSS, images, fonts)
  - `runtime/component/*/component.xml` (component configuration)
- **Base component**:
  - `runtime/base-component/webroot/` (theme and core web assets)
- **Build configuration**:
  - `build.gradle`, `framework/build.gradle`, `runtime/component/*/build.gradle`
  - `gradle.properties`, `settings.gradle`

**Global excludes**:

- `build/`, `.gradle/`, `runtime/lib/`
- `runtime/elasticsearch/`, `runtime/db/`, `runtime/log/`
- `.git/`, `node_modules/`

---

## 4. Tools and Config Files

The Lint Agent recommends or uses these tools:

### 4.1 Java 11

**Recommended**: Checkstyle (via Gradle plugin)
- Config: `config/checkstyle/checkstyle.xml` (to be created)
- Gradle task: `./gradlew checkstyleMain checkstyleTest`
- Checks: Imports, naming, formatting, documentation

**Optional**: SpotBugs for deeper static analysis

### 4.2 Groovy

**Recommended**: CodeNarc (via Gradle plugin)
- Config: `config/codenarc/CodeNarcRules.groovy` (to be created)
- Gradle task: `./gradlew codenarcMain codenarcTest`
- Checks: Complexity, naming, style, best practices

### 4.3 XML / Moqui DSL

**Built-in**: XML schema validation against Moqui XSD files in `framework/xsd/`

**Recommended**: Custom Moqui XML validator (Groovy Gradle task)
- Task: `./gradlew validateMoquiXml`
- Validates: Entity definitions, service definitions, screen definitions
- Checks: Required attributes, naming conventions, audit fields

### 4.4 Markdown

**Recommended**: markdownlint
- Install: `npm install -D markdownlint-cli`
- Command: `markdownlint docs/`
- Validates: Markdown structure, link validity, formatting

### 4.5 Gradle Build Configuration

**Approach**: Manual review + optional Gradle Lint Plugin
- Check: Repository configuration, dependency versions, path consistency
- Gradle Lint Plugin: Optional for build best practices

---

## 5. Conventions by Technology

### 5.1 Java 11

- **Language level**: Java 11 (sourceCompatibility = 11)
- **Naming**:
  - Classes: PascalCase (e.g., `ExecutionContext`, `EntityFacade`)
  - Methods: camelCase (e.g., `createEntity`)
  - Constants: UPPER_SNAKE_CASE
- **Style**:
  - 4-space indentation (no tabs)
  - Braces on same line (Java style)
  - No wildcard imports
  - No unused imports
- **Moqui patterns**:
  - Use ExecutionContext for coordination
  - Proper transaction handling
  - Explicit error handling
- **Error policy**: Build fails on Checkstyle violations

### 5.2 Groovy

- **Naming**: Same as Java (PascalCase/camelCase)
- **Code quality**:
  - Avoid unnecessary dynamic typing
  - Method complexity < 15 (McCabe)
  - Explicit closure parameters (avoid implicit `it`)
  - No unused variables/imports
- **Moqui services**:
  - Stateless and idempotent where possible
  - Proper logging (use EC.logger, not println)
  - File naming: `ServiceName.groovy`
- **Error policy**: CodeNarc violations fail build

### 5.3 JavaScript/CSS

- **File organization**:
  - Theme files in `runtime/base-component/webroot/` or `runtime/component/*/webroot/`
  - Assets: `css/`, `js/`, `img/`, `fonts/` directories
  - Files: lowercase with hyphens (e.g., `dark-theme.css`)
- **CSS conventions**:
  - Consistent naming (BEM or utility-based)
  - No inline styles
  - Comment complex rules
- **JavaScript**:
  - Use ES6+ where supported
  - Prefer const/let over var
  - Comment non-obvious logic
- **Tools**: ESLint + Prettier (recommended)

### 5.5 Component-Specific Conventions

This project uses a **component-based architecture** where most functionality lives in `runtime/component/` modules. Each component should follow:

#### Component Structure
```
runtime/component/ComponentName/
├── component.xml              # Component metadata and dependencies
├── build.gradle              # Component-specific build config
├── screen/                   # Screen definitions (organized by domain)
│   ├── ComponentNameAdmin/   # Admin screens
│   └── ComponentName/        # End-user screens
├── service/                  # Service definitions (organized by domain)
│   ├── org/moqui/component/  # Service implementation packages
│   └── Domain/               # Grouped by business domain
├── entity/                   # Entity definitions
├── data/                     # Seed data and test data
├── src/main/groovy/          # Service implementations and scripts
├── src/main/java/            # Java implementations (if any)
└── webroot/                  # Web-accessible resources
    ├── css/                  # Stylesheets
    ├── js/                   # JavaScript files
    ├── img/                  # Images and icons
    └── fonts/                # Font files
```

#### Service Definition Naming (in service/*.xml)
- Format: `domain#ServiceName` (e.g., `create#Order`, `update#Customer`, `get#OrderTotal`)
- Verbs: create, update, delete, find, get, check, run, process
- Parameters: All inputs/outputs explicitly defined with types
- Documentation: Service description and all parameter descriptions required

#### Screen Definition Naming (in screen/*.xml)
- Location reflects domain and hierarchy: `ComponentNameAdmin/OrderList.xml`
- File names: PascalCase (OrderList.xml, EditOrder.xml, OrderDetails.xml)
- Menu organization: Use consistent menu-index values within component
- Subscreens: Link to related screens from same or dependent components

#### Entity Definition Naming (in entity/*.xml)
- Names: PascalCase, domain-aware (Order, OrderItem, OrderHeader, Customer, CustomerAccount)
- Primary keys: Always defined, typically `Id` or `compoundId` fields
- Relationships: Clear naming showing relationship direction
- Audit fields: createdStamp, lastUpdatedStamp, createdByUserId recommended

#### Component Dependency Management
- Dependencies declared in `component.xml` using proper syntax
- Components should minimize circular dependencies
- Reference other component screens: `component://ComponentName/screen/...`
- Version management via `build.gradle` and `addons.xml`

### 5.4 XML / Moqui DSL

- **General XML**:
  - Well-formed (valid structure)
  - Proper encoding declaration
  - Validate against Moqui XSD schemas
- **Entity Definitions**:
  - Names: PascalCase (e.g., `WorkOrder`)
  - Must have primary key(s)
  - Audit fields: `createdStamp`, `lastUpdatedStamp` (for transactional entities)
  - Field naming: camelCase
  - Description attributes recommended
  - Proper relationship definitions
- **Service Definitions**:
  - Naming: `domain#ServiceName` (e.g., `create#WorkOrder`)
  - All parameters: explicit types
  - Parameter descriptions required
  - Input validation
  - Clear return value documentation
- **Screen Definitions**:
  - Location reflects domain (e.g., `WorkOrder/EditWorkOrder.xml`)
  - Transitions: minimal logic, prefer service delegation
  - Forms: validate against entity definitions
  - Components: use includes for reusability

The custom XML validator flags convention violations.

---

## 5.6 Reference Components in This Project

This project includes several well-structured components to use as linting references:

- **SimpleScreens** (`runtime/component/SimpleScreens/`) - Reference implementation for screen organization and service integration patterns
- **PopCommerce** (`runtime/component/PopCommerce/`) - E-commerce component with comprehensive service and entity definitions
- **HiveMind** (`runtime/component/HiveMind/`) - Project management component showing complex entity relationships
- **ManageERP** (`runtime/component/MarbleERP/`) - ERP-style component with service hierarchies
- **mantle-udm** (`runtime/component/mantle-udm/`) - Universal Data Model with base entities
- **mantle-usl** (`runtime/component/mantle-usl/`) - Universal Service Library with reusable services
- **example** (`runtime/component/example/`) - Simple reference component for basic patterns

Use these as examples when linting new components or features.

## Integration with Other Agents

- **Validate code from `moqui_developer_agent`** - Enforce style and quality standards on all implementations
- **Coordinate with `architecture_agent`** for component structure and naming conventions
- **Work with `test_agent`** to ensure test code follows same quality standards
- **Report violations back to `moqui_developer_agent`** for resolution before code completion
- **Collaborate with `docs_agent`** to enforce documentation standards in code comments

---

## 6. Unified Lint Commands

### 6.1 Local Lint (Preferred Entry Point)

At the repository root:

```bash
./lint-all.sh
