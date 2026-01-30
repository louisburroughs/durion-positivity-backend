# Version Management for Durion Positivity Backend

This guide explains how to use Maven Versions Plugin for automated version management.

## Quick Start

### Using the Script (Recommended)

```bash
# Make script executable (first time only)
chmod +x scripts/update-version.sh

# Preview changes (does NOT commit)
./scripts/update-version.sh patch

# Bump patch version and auto-commit
./scripts/update-version.sh patch --commit

# Bump minor version (0.0.1 → 0.1.0-SNAPSHOT)
./scripts/update-version.sh minor --commit

# Bump major version (0.1.0 → 1.0.0-SNAPSHOT)
./scripts/update-version.sh major --commit
```

### Manual Maven Command

```bash
# View current version
mvn help:evaluate -Dexpression=project.version -q -DforceStdout

# Set specific version across all modules
mvn versions:set -DnewVersion=0.1.0-SNAPSHOT -DprocessAllModules

# Preview changes before committing
git diff pom.xml **/pom.xml

# Commit if satisfied
git add pom.xml **/pom.xml
git commit -m "chore: bump version to 0.1.0-SNAPSHOT"
```

## Version Scheme

This project follows **Semantic Versioning**: `MAJOR.MINOR.PATCH-SNAPSHOT`

- **MAJOR**: Breaking changes to APIs or architecture (e.g., Spring Boot 4.0 → 4.1 migration)
- **MINOR**: New features, backwards-compatible changes
- **PATCH**: Bug fixes, small improvements
- **-SNAPSHOT**: Development version (automatic, indicates pre-release)

### Current Version
- **Development**: `0.0.1-SNAPSHOT` (in progress)
- **After Spring Boot 4 migration**: Will bump to `0.1.0-SNAPSHOT`
- **Production release**: Remove `-SNAPSHOT` suffix → `0.1.0`

## Workflow

### Development (Creating SNAPSHOT versions)

```bash
# During feature development, versions stay as -SNAPSHOT
./scripts/update-version.sh minor --commit  # 0.0.1 → 0.1.0-SNAPSHOT
```

### Release (Removing SNAPSHOT for production)

```bash
# When ready for release, use Maven Release Plugin
mvn release:prepare release:perform

# Or manually:
# 1. Remove -SNAPSHOT
mvn versions:set -DnewVersion=0.1.0 -DprocessAllModules

# 2. Commit and tag
git add pom.xml **/pom.xml
git commit -m "chore: release version 0.1.0"
git tag v0.1.0

# 3. Bump to next -SNAPSHOT version
mvn versions:set -DnewVersion=0.2.0-SNAPSHOT -DprocessAllModules
git add pom.xml **/pom.xml
git commit -m "chore: prepare for next development cycle (0.2.0-SNAPSHOT)"

# 4. Push everything
git push origin main --tags
```

## Maven Versions Plugin Commands

### Check for available updates

```bash
# Show dependency updates available
mvn versions:display-dependency-updates

# Show plugin updates available
mvn versions:display-plugin-updates

# Show property updates available
mvn versions:display-property-updates
```

### Update specific dependencies

```bash
# Update a single dependency
mvn versions:use-latest-versions -Dincludes=org.springframework.boot:*

# Update to specific version
mvn versions:use-specific-versions -Dincludes=org.springframework.boot:spring-boot-starter-web:4.1.0
```

### Rollback changes

```bash
# Maven versions plugin creates backup files (disabled in our config)
# If backups were enabled, restore with:
mvn versions:revert

# Since we disabled backups, use git to revert:
git checkout pom.xml **/pom.xml
```

## Configuration

The Maven Versions Plugin is configured in `pom.xml`:

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

**Configuration Options:**
- `generateBackupPoms=false`: Don't create backup files (use git instead)

## Best Practices

1. **Always preview before committing**
   ```bash
   ./scripts/update-version.sh patch    # Preview
   git diff pom.xml                     # Review
   ./scripts/update-version.sh patch --commit  # Commit
   ```

2. **Use semantic versioning consistently**
   - Minor bumps for feature releases
   - Patch bumps for bug fixes only
   - Major bumps for breaking changes

3. **Commit version changes in dedicated commits**
   - Keep version bumps separate from feature commits
   - Use clear commit messages: `chore: bump version to X.Y.Z`

4. **Tag releases in git**
   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```

5. **Update CHANGELOG**
   - Document what changed in each version
   - Link to related PRs/issues

## Troubleshooting

### Issue: Version not updating in all modules

**Solution**: Ensure `-DprocessAllModules` is included:
```bash
mvn versions:set -DnewVersion=0.1.0-SNAPSHOT -DprocessAllModules
```

### Issue: Wrong version in child modules

**Solution**: Check pom.xml parent references:
```bash
# All modules should reference parent with ${project.version}
grep -r "<version>" pom.xml **/pom.xml | grep -v "project.version"
```

### Issue: Need to revert version change

**Solution**: Use git to revert:
```bash
git checkout pom.xml **/pom.xml
```

## CI/CD Integration

To integrate version updates into your CI/CD pipeline, see `.github/workflows/release.yml` for GitHub Actions example.

## References

- [Maven Versions Plugin Documentation](https://www.mojohaus.org/versions/versions-maven-plugin/)
- [Semantic Versioning](https://semver.org/)
- [Maven Release Plugin](https://maven.apache.org/maven-release/maven-release-plugin/)
