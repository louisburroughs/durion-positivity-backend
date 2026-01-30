#!/bin/bash
# update-version.sh - Automated semantic versioning for Durion Positivity Backend
# Usage: ./scripts/update-version.sh [patch|minor|major] [--commit]

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
COMMIT_FLAG=false

# Parse arguments
VERSION_TYPE="${1:-patch}"
if [[ "$2" == "--commit" ]]; then
    COMMIT_FLAG=true
fi

# Validate version type
case "$VERSION_TYPE" in
    patch|minor|major)
        ;;
    *)
        echo "❌ Invalid version type: $VERSION_TYPE"
        echo "Usage: $0 [patch|minor|major] [--commit]"
        exit 1
        ;;
esac

cd "$PROJECT_ROOT"

# Extract current version (remove -SNAPSHOT suffix)
# Use grep to find version after artifactId, not parent version
CURRENT_VERSION=$(grep -A1 '<artifactId>positivity</artifactId>' pom.xml | grep '<version>' | sed -E 's/.*<version>(.*)<\/version>.*/\1/' | sed 's/-SNAPSHOT//')

echo "📦 Current version: $CURRENT_VERSION"

# Parse version components
IFS='.' read -r MAJOR MINOR PATCH <<< "$CURRENT_VERSION"

# Calculate new version
case "$VERSION_TYPE" in
    major)
        MAJOR=$((MAJOR + 1))
        MINOR=0
        PATCH=0
        ;;
    minor)
        MINOR=$((MINOR + 1))
        PATCH=0
        ;;
    patch)
        PATCH=$((PATCH + 1))
        ;;
esac

NEW_VERSION="$MAJOR.$MINOR.$PATCH-SNAPSHOT"

echo "🚀 Updating to version: $NEW_VERSION"
echo ""

# Update all modules with versions plugin
echo "⏳ Updating all pom.xml files..."
./mvnw versions:set -DnewVersion="$NEW_VERSION" -DprocessAllModules -q

echo "✅ Version updated successfully"
echo ""
echo "Changed files:"
git diff --name-only pom.xml **/pom.xml | sort

echo ""
echo "Preview of changes:"
git diff pom.xml | head -20

# Optionally commit
if [[ "$COMMIT_FLAG" == true ]]; then
    echo ""
    echo "⏳ Committing changes..."
    git add pom.xml **/pom.xml
    git commit -m "chore: bump version to $NEW_VERSION"
    echo "✅ Committed with message: chore: bump version to $NEW_VERSION"
    echo ""
    echo "📝 Next steps:"
    echo "   - Review: git log -1 -p"
    echo "   - Push:   git push origin $(git rev-parse --abbrev-ref HEAD)"
else
    echo ""
    echo "⚠️  Changes NOT committed. Review and commit manually:"
    echo "   git add pom.xml **/pom.xml"
    echo "   git commit -m \"chore: bump version to $NEW_VERSION\""
    echo ""
    echo "📝 Or run this script with --commit flag to auto-commit:"
    echo "   $0 $VERSION_TYPE --commit"
fi
