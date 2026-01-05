#!/bin/bash

# Story Strengthening Agent - Bash Execution Script
# Usage: ./strengthen-story.sh <repository> <issue-numbers> [options]

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default values
OUTPUT_DIR="./story-output"
BYPASS_SSL=""
BATCH_MODE=""

# Function to display usage
usage() {
    echo "Story Strengthening Agent - Execution Script"
    echo ""
    echo "Usage: $0 <repository> <issue-numbers> [options]"
    echo ""
    echo "Arguments:"
    echo "  <repository>       Repository in format 'owner/repo'"
    echo "  <issue-numbers>    Issue number(s) to process"
    echo "                     Single: 123"
    echo "                     Multiple: 123,124,125"
    echo "                     Range: 123-127"
    echo ""
    echo "Options:"
    echo "  --output-dir <path>    Output directory (default: ./story-output)"
    echo "  --bypass-ssl           Bypass SSL certificate validation (development only)"
    echo "  --batch                Enable batch mode (no confirmation prompts)"
    echo "  --help                 Display this help message"
    echo ""
    echo "Environment Variables:"
    echo "  GITHUB_TOKEN           GitHub personal access token (required)"
    echo ""
    echo "Examples:"
    echo "  Single issue:"
    echo "    $0 louisburroughs/durion-positivity-backend 123"
    echo "  Multiple issues:"
    echo "    $0 louisburroughs/durion-positivity-backend 123,124,125"
    echo "  Range of issues:"
    echo "    $0 louisburroughs/durion-positivity-backend 123-127"
    echo "  Batch mode (no prompts):"
    echo "    $0 louisburroughs/durion-positivity-backend 123,124,125 --batch"
    exit 1
}

# Check for help flag
if [[ "$1" == "--help" ]] || [[ "$1" == "-h" ]]; then
    usage
fi

# Check minimum arguments
if [ $# -lt 2 ]; then
    echo -e "${RED}❌ Error: Missing required arguments${NC}"
    echo ""
    usage
fi

# Parse arguments
REPOSITORY="$1"
ISSUE_NUMBERS="$2"
shift 2

# Parse options
while [[ $# -gt 0 ]]; do
    case $1 in
        --output-dir)
            OUTPUT_DIR="$2"
            shift 2
            ;;
        --bypass-ssl)
            BYPASS_SSL="--bypass-ssl"
            shift
            ;;
        --batch)
            BATCH_MODE="--batch"
            shift
            ;;
        *)
            echo -e "${RED}❌ Error: Unknown option: $1${NC}"
            usage
            ;;
    esac
done

# Check for GitHub token
if [ -z "$GITHUB_TOKEN" ]; then
    echo -e "${RED}❌ Error: GITHUB_TOKEN environment variable not set${NC}"
    echo ""
    echo "Please set your GitHub personal access token:"
    echo "  export GITHUB_TOKEN=your_token_here"
    echo ""
    exit 1
fi

# Display configuration
echo -e "${BLUE}🚀 Story Strengthening Agent${NC}"
echo "=============================="
echo -e "${BLUE}Repository:${NC} $REPOSITORY"
echo -e "${BLUE}Issue Numbers:${NC} $ISSUE_NUMBERS"
echo -e "${BLUE}Output Directory:${NC} $OUTPUT_DIR"
if [ -n "$BYPASS_SSL" ]; then
    echo -e "${YELLOW}⚠️ SSL Bypass:${NC} Enabled (development mode)"
fi
if [ -n "$BATCH_MODE" ]; then
    echo -e "${YELLOW}⚠️ Batch Mode:${NC} Enabled (no confirmation prompts)"
fi
echo ""

# Check if Maven is available
if ! command -v mvn &> /dev/null; then
    echo -e "${RED}❌ Error: Maven (mvn) not found in PATH${NC}"
    echo "Please install Maven or ensure it's in your PATH"
    exit 1
fi

# Build the project
echo -e "${BLUE}🔨 Building project...${NC}"
mvn clean compile -q
if [ $? -ne 0 ]; then
    echo -e "${RED}❌ Build failed${NC}"
    exit 1
fi
echo -e "${GREEN}✅ Build successful${NC}"
echo ""

# Run the Story Strengthening System
echo -e "${BLUE}🔄 Starting story strengthening process...${NC}"
echo ""

mvn exec:java \
    -Dexec.mainClass="com.pos.agent.story.StoryStrengtheningSystem" \
    -Dexec.args="$REPOSITORY $ISSUE_NUMBERS --output-dir $OUTPUT_DIR $BYPASS_SSL $BATCH_MODE" \
    -Dexec.cleanupDaemonThreads=false

EXIT_CODE=$?

echo ""
if [ $EXIT_CODE -eq 0 ]; then
    echo -e "${GREEN}✅ Story strengthening completed successfully!${NC}"
elif [ $EXIT_CODE -eq 2 ]; then
    echo -e "${YELLOW}⚠️ Story strengthening completed with some failures${NC}"
else
    echo -e "${RED}❌ Story strengthening failed with exit code: $EXIT_CODE${NC}"
fi

exit $EXIT_CODE
