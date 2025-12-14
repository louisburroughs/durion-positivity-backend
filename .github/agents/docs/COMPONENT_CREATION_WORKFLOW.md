# Component Creation Workflow

## Overview

This guide provides a complete workflow for creating new Moqui components, managing them in `myaddons.xml`, creating GitHub repositories, and checking in code using agents.

## Pre-flight Checklist

Before creating a new component, ensure:

- [ ] Component name follows Durion naming convention: `durion-{domain}`
- [ ] Component purpose is clear and aligned with DDD boundaries
- [ ] Architecture agent has approved the component placement
- [ ] No existing component covers this domain
- [ ] GitHub organization access is configured (`gh auth login`)

## Workflow Steps

### Step 1: Component Design & Approval

**Agent:** `@architecture-agent`

Ask the architecture agent to validate your component design:

```
@architecture-agent Should I create a new component for shipping management, 
or should this functionality go into an existing component like durion-inventory?
```

**Expected Output:**
- Domain boundary analysis
- Component placement recommendation
- Entity and service guidance
- Dependency recommendations

### Step 2: Create Component Structure

**Agent:** `@moquiDeveloper-agent`

Request component creation:

```
@moquiDeveloper-agent Create a new component called durion-shipping 
for shipping and logistics management
```

**What the Agent Does:**
1. Creates component directory: `runtime/component/durion-shipping/`
2. Generates `component.xml` with metadata
3. Creates standard directory structure:
   ```
   durion-shipping/
   ├── component.xml
   ├── data/
   ├── entity/
   ├── screen/
   ├── service/
   ├── src/
   └── template/
   ```
4. Adds basic README.md
5. Creates initial entity definitions (if specified)

**Manual Alternative:**
```bash
cd runtime/component
mkdir -p durion-shipping/{data,entity,screen,service,src,template}
```

### Step 3: Update myaddons.xml

**Agent:** `@moquiDeveloper-agent`

Request myaddons.xml update:

```
@moquiDeveloper-agent Add durion-shipping to myaddons.xml 
with group louisburroughs, version 1, branch main
```

**Expected Entry:**
```xml
<component name="durion-shipping" group="louisburroughs" version="1" branch="main"/>
```

**Manual Alternative:**
```bash
# Edit myaddons.xml directly
vim myaddons.xml
```

### Step 4: Initialize Git Repository

**Agent:** `@dev-deploy-agent`

Request git initialization:

```
@dev-deploy-agent Initialize git repository for durion-shipping component
```

**What the Agent Does:**
1. Creates `.gitignore` for Moqui components
2. Initializes git: `git init`
3. Creates initial commit with component structure
4. Sets up branch: `main`

**Manual Alternative:**
```bash
cd runtime/component/durion-shipping
git init
cat > .gitignore << 'EOF'
# Build artifacts
build/
*.class
*.jar

# IDE files
.idea/
*.iml
.vscode/

# Logs
logs/
*.log

# OS files
.DS_Store
Thumbs.db
EOF

git add .
git commit -m "Initial commit: durion-shipping component structure"
```

### Step 5: Create GitHub Repository

**Agent:** `@dev-deploy-agent`

Request GitHub repository creation:

```
@dev-deploy-agent Create GitHub repository louisburroughs/durion-shipping 
and push the code
```

**What the Agent Does:**
1. Creates repository using `gh` CLI:
   ```bash
   gh repo create louisburroughs/durion-shipping --public --source=. --remote=origin
   ```
2. Pushes code: `git push -u origin main`
3. Sets up branch protection (optional)
4. Adds repository topics and description

**Manual Alternative:**
```bash
cd runtime/component/durion-shipping

# Create repo
gh repo create louisburroughs/durion-shipping \
  --public \
  --description "Durion Shipping and Logistics Component" \
  --source=. \
  --remote=origin

# Push code
git push -u origin main
```

### Step 6: Verify Component Integration

**Agent:** `@test-agent`

Request integration verification:

```
@test-agent Verify that durion-shipping component loads correctly in Moqui
```

**What to Verify:**
1. Component appears in Moqui Tools > Components
2. Entities are registered (if any)
3. Services are available (if any)
4. No startup errors in logs

**Manual Verification:**
```bash
# Start Moqui
./gradlew run

# Check logs
tail -f runtime/log/moqui.log | grep durion-shipping

# Access Moqui Tools
# Navigate to: https://localhost:8080/tools/System/ComponentList
```

## Component Structure Template

### Minimal Component

```
durion-{domain}/
├── component.xml           # Component metadata
├── README.md              # Component documentation
├── data/                  # Seed/demo data
├── entity/                # Entity definitions
│   └── {Domain}Entities.xml
├── screen/                # UI screens
│   └── {Domain}Screens.xml
├── service/               # Service definitions
│   └── {Domain}Services.xml
└── src/                   # Groovy/Java source
    └── main/groovy/
```

### Full Component

```
durion-{domain}/
├── component.xml
├── README.md
├── ReleaseNotes.md
├── LICENSE.md
├── build.gradle           # If custom build needed
├── data/
│   ├── {Domain}DemoData.xml
│   └── {Domain}SecurityData.xml
├── entity/
│   ├── {Domain}Entities.xml
│   └── {Domain}ViewEntities.xml
├── screen/
│   ├── {Domain}Screens.xml
│   └── {Domain}Forms.xml
├── service/
│   ├── {Domain}Services.xml
│   └── {Domain}Interfaces.xml
├── src/
│   ├── main/
│   │   └── groovy/
│   └── test/
│       └── groovy/
├── template/
│   └── email/
└── test/
    └── {Domain}Tests.xml
```

## Component Naming Conventions

### Pattern
```
durion-{domain}
```

### Examples
- `durion-shipping` - Shipping and logistics
- `durion-warehouse` - Warehouse management
- `durion-procurement` - Procurement and purchasing
- `durion-quality` - Quality assurance
- `durion-scheduling` - Production scheduling

### Rules
1. All lowercase
2. Hyphen-separated
3. Starts with `durion-`
4. Describes single domain/bounded context
5. No abbreviations unless universally understood

## myaddons.xml Management

### XML Structure
```xml
<?xml version="1.0" encoding="UTF-8"?>
<addons>
    <!-- Custom or overridden component definitions -->
    
    <!-- durion-theme component -->
    <component name="durion-theme" group="louisburroughs" version="1" branch="main"/>
    
    <!-- durion-shipping component -->
    <component name="durion-shipping" group="louisburroughs" version="1" branch="main"/>
</addons>
```

### Attributes
- `name` - Component directory name
- `group` - GitHub organization/user
- `version` - Component version (increment on breaking changes)
- `branch` - Git branch to track (usually `main`)

### Order
Components should be listed in dependency order where possible:
1. Foundation components (durion-common)
2. Business domain components
3. Integration components (durion-positivity)
4. UI/Theme components (durion-theme)

## GitHub Repository Configuration

### Repository Settings

**Recommended:**
- **Visibility:** Public (for open-source) or Private
- **Topics:** `moqui`, `durion`, `erp`, `{domain}`
- **Description:** Clear one-line description
- **Website:** Link to documentation
- **License:** Same as main project

### Branch Protection

Enable for `main` branch:
- [ ] Require pull request reviews (1 approver)
- [ ] Require status checks to pass
- [ ] Require branches to be up to date
- [ ] Require conversation resolution

### GitHub Actions

Create `.github/workflows/build.yml`:
```yaml
name: Build and Test

on: [push, pull_request]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '11'
      - name: Build with Gradle
        run: ./gradlew build
```

## Agent Collaboration Pattern

### Who Does What

| Task | Primary Agent | Supporting Agent |
|------|---------------|------------------|
| Validate domain boundaries | `@architecture-agent` | - |
| Create component structure | `@moquiDeveloper-agent` | `@architecture-agent` |
| Update myaddons.xml | `@moquiDeveloper-agent` | - |
| Initialize git | `@dev-deploy-agent` | - |
| Create GitHub repo | `@dev-deploy-agent` | - |
| Set up CI/CD | `@dev-deploy-agent` | `@test-agent` |
| Verify integration | `@test-agent` | `@moquiDeveloper-agent` |
| Document component | `@docs-agent` | `@architecture-agent` |

### Example Complete Workflow

```
# 1. Design validation
@architecture-agent Analyze if shipping management should be a new component 
or part of durion-inventory

# 2. Create component
@moquiDeveloper-agent Create durion-shipping component with entities:
- Shipment
- ShipmentItem
- ShipmentPackage
- Carrier

# 3. Update addons
@moquiDeveloper-agent Add durion-shipping to myaddons.xml

# 4. Initialize git and GitHub
@dev-deploy-agent Initialize git repo for durion-shipping and create 
GitHub repository at louisburroughs/durion-shipping

# 5. Set up CI/CD
@dev-deploy-agent Create GitHub Actions workflow for durion-shipping

# 6. Verify
@test-agent Verify durion-shipping loads and entities are registered

# 7. Document
@docs-agent Create README for durion-shipping explaining shipping workflows
```

## Troubleshooting

### Component Not Loading

**Symptoms:**
- Component missing from Moqui Tools
- "Component not found" errors in logs

**Solutions:**
1. Verify `component.xml` is valid XML
2. Check component name matches directory name
3. Restart Moqui: `./gradlew cleanAll run`
4. Check for conflicting component names

### myaddons.xml Errors

**Symptoms:**
- "Could not parse myaddons.xml" errors
- Components not being loaded from GitHub

**Solutions:**
1. Validate XML syntax: `xmllint --noout myaddons.xml`
2. Check GitHub group/org name is correct
3. Verify branch exists in remote repository
4. Ensure SSH keys are configured for GitHub

### Git Push Failures

**Symptoms:**
- Permission denied
- Remote repository not found

**Solutions:**
1. Check GitHub authentication: `gh auth status`
2. Verify repository exists: `gh repo view louisburroughs/durion-shipping`
3. Check remote URL: `git remote -v`
4. Re-authenticate: `gh auth login`

### Build Failures

**Symptoms:**
- Gradle build fails
- Missing dependencies

**Solutions:**
1. Check `build.gradle` for syntax errors
2. Verify dependency declarations in `component.xml`
3. Run `./gradlew --refresh-dependencies`
4. Check for circular dependencies

## Best Practices

### Component Design
1. **Single Responsibility** - One domain per component
2. **Clear Boundaries** - Minimize cross-component dependencies
3. **Standard Structure** - Follow Moqui conventions
4. **Documentation** - Include README with purpose and usage

### Version Control
1. **Atomic Commits** - One logical change per commit
2. **Meaningful Messages** - Explain why, not what
3. **Feature Branches** - Develop on branches, merge to main
4. **Tags for Releases** - Use semantic versioning

### GitHub Management
1. **Descriptive Names** - Repository name = component name
2. **Clear README** - Installation, configuration, usage
3. **License File** - Include LICENSE.md
4. **Issue Templates** - Standardize bug reports and feature requests

### Agent Usage
1. **Context First** - Provide full context to agents
2. **Iterative Refinement** - Review agent output, provide feedback
3. **Validation** - Always verify agent-generated code
4. **Documentation** - Document decisions for future reference

## Quick Reference

### Create New Component (Full Workflow)
```bash
# 1. Ask architecture agent for approval
@architecture-agent Should I create durion-{domain}?

# 2. Create component
@moquiDeveloper-agent Create durion-{domain} component

# 3. Update addons
@moquiDeveloper-agent Add durion-{domain} to myaddons.xml

# 4. Git + GitHub
@dev-deploy-agent Initialize git and create GitHub repo for durion-{domain}

# 5. Verify
@test-agent Verify durion-{domain} integration
```

### Manual Component Creation
```bash
# Create structure
cd runtime/component
mkdir durion-{domain}
cd durion-{domain}
mkdir -p data entity screen service src/main/groovy template

# Create component.xml
cat > component.xml << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<component xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
           xsi:noNamespaceSchemaLocation="http://moqui.org/xsd/moqui-conf-3.xsd"
           name="durion-{domain}" version="1.0.0">
    <description>Durion {Domain} Component</description>
    <depends-on name="durion-common"/>
</component>
EOF

# Initialize git
git init
git add .
git commit -m "Initial commit"

# Create GitHub repo
gh repo create louisburroughs/durion-{domain} --public --source=. --remote=origin
git push -u origin main

# Update myaddons.xml
# Add: <component name="durion-{domain}" group="louisburroughs" version="1" branch="main"/>
```

## Related Documentation

- [Agent Collaboration Framework](../AGENT_COLLABORATION.md)
- [Architecture Agent Guide](../agents/architecture-agent.md)
- [Developer Agent Guide](../agents/moquiDeveloper-agent.md)
- [DevOps Agent Guide](../agents/dev-deploy-agent.md)
- [Moqui Framework Documentation](https://www.moqui.org/m/docs)

---

**Last Updated:** December 9, 2025
