# Durion Positivity Backend - Scripts

Utility scripts for managing the project build, versioning, and deployment.

## Available Scripts

### `generate-openapi.sh` - Per-Module + Aggregate OpenAPI Generation

Generates `openapi.yaml` for every configured module and then creates an aggregate index spec.

**Usage:**
```bash
./generate-openapi.sh [options] [module...]
```

**Examples:**

```bash
# Generate for all configured modules + aggregate file
./generate-openapi.sh

# Generate only selected modules + aggregate file
./generate-openapi.sh pos-api-gateway pos-workorder

# Generate and write aggregate file to a custom location
./generate-openapi.sh --aggregate-output docs/openapi-aggregate.yaml

# Generate module specs only (skip aggregate)
./generate-openapi.sh --no-aggregate
```

**What it does:**
1. Discovers modules configured to output `openapi.yaml`
2. Runs Maven generation per module (`verify` by default)
3. Produces aggregate index spec at `pos-api-gateway/docs/openapi-aggregate.yaml` by default
    - The aggregate file uses `$ref` pointers to each module's `openapi.yaml`
    - Duplicate path keys across modules are skipped and listed in `x-duplicate-paths-skipped`

**Notes:**
- Requires `python3` and `PyYAML` for aggregate generation.
- Module generation still works if aggregate generation is disabled via `--no-aggregate`.

### `update-version.sh` - Semantic Version Management

Automated semantic versioning for the multi-module Maven project.

**Usage:**
```bash
./update-version.sh [patch|minor|major] [--commit]
```

**Examples:**

```bash
# Preview patch bump (0.1.0 → 0.1.1-SNAPSHOT)
./update-version.sh patch

# Bump minor version and auto-commit
./update-version.sh minor --commit

# Bump major version and auto-commit
./update-version.sh major --commit
```

**What it does:**
1. Extracts current version from root `pom.xml`
2. Calculates new version based on semantic versioning rules
3. Updates ALL 27 module `pom.xml` files using Maven Versions Plugin
4. Displays preview of changes
5. Optionally commits changes (with `--commit` flag)

**Key Features:**
- ✅ Safe preview mode (no --commit = no changes written)
- ✅ Updates all 27 modules automatically
- ✅ Semantic versioning support (major/minor/patch)
- ✅ Clear git workflow instructions
- ✅ Smart version extraction (ignores Spring Boot parent version)

**Output Example:**
```
📦 Current version: 0.1.0
🚀 Updating to version: 0.2.0-SNAPSHOT

⏳ Updating all pom.xml files...
✅ Version updated successfully

Changed files:
pom.xml
pos-accounting/pom.xml
pos-agent-framework/pom.xml
... (27 modules total)

Preview of changes:
diff --git a/pom.xml b/pom.xml
-       <version>0.1.0-SNAPSHOT</version>
+       <version>0.2.0-SNAPSHOT</version>
```

## Version Management Workflow

### Standard Development Flow

```bash
# 1. Work on features in -SNAPSHOT version
#    (currently 0.1.0-SNAPSHOT)

# 2. When ready to release, bump version
./update-version.sh minor --commit

# 3. Create release (without -SNAPSHOT)
./mvnw release:prepare release:perform
```

### Semantic Versioning Rules

| Type  | When to use | Example |
|-------|-----------|---------|
| Patch | Bug fixes | 0.1.0 → 0.1.1 |
| Minor | New features (backwards-compatible) | 0.1.0 → 0.2.0 |
| Major | Breaking changes | 0.2.0 → 1.0.0 |

### Release Workflow

```bash
# 1. Bump version to next development cycle
./update-version.sh minor --commit

# 2. Create git tag for the release
git tag v0.2.0

# 3. Push to remote
git push origin main --tags
```

## Manual Version Commands

If you prefer not to use the script:

```bash
# Check current version
./mvnw help:evaluate -Dexpression=project.version -q -DforceStdout

# Set specific version
./mvnw versions:set -DnewVersion=0.2.0-SNAPSHOT -DprocessAllModules

# Review changes
git diff pom.xml **/pom.xml

# Commit if satisfied
git add pom.xml **/pom.xml
git commit -m "chore: bump version to 0.2.0-SNAPSHOT"
```

## Configuration

The Maven Versions Plugin is configured in the root `pom.xml`:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-versions-plugin</artifactId>
    <version>2.16.2</version>
    <configuration>
        <generateBackupPoms>false</generateBackupPoms>
    </configuration>
</plugin>
```

## Troubleshooting

### Script Permission Error

```bash
chmod +x scripts/update-version.sh
```

### Maven Not Found

The script uses `./mvnw` (Maven Wrapper). If it's not available:
```bash
# Use system Maven if available
mvn versions:set -DnewVersion=0.2.0-SNAPSHOT -DprocessAllModules
```

### Wrong Version Detected

The script intelligently skips the Spring Boot parent version (4.0.1) and reads the actual project version. If it's still wrong, check that `pom.xml` has the correct version element after the `<artifactId>positivity</artifactId>` tag.

### Undo Version Changes

```bash
git checkout pom.xml **/pom.xml
```

## Additional Resources

- See [VERSION_MANAGEMENT.md](../docs/VERSION_MANAGEMENT.md) for comprehensive guide
- [Maven Versions Plugin](https://www.mojohaus.org/versions/versions-maven-plugin/)
- [Semantic Versioning](https://semver.org/)
