# Story Strengthening Agent - Execution Guide

## Overview

The Story Strengthening Agent is a comprehensive system that processes GitHub issues containing user stories and strengthens them by:

- Validating story format and structure
- Parsing and analyzing requirements
- Transforming requirements into EARS patterns
- Generating Gherkin acceptance criteria
- Detecting ambiguities and generating open questions
- Ensuring ISO/IEC/IEEE 29148 compliance
- Detecting processing loops and unsafe inferences

## Prerequisites

1. **Java 21** - Required runtime
2. **Maven** - Build system (Maven wrapper included)
3. **GitHub Personal Access Token** - For API access
   - Create at: https://github.com/settings/tokens
   - Required scopes: `repo` (for private repos) or `public_repo` (for public repos)

## Quick Start

### 1. Set GitHub Token

**Linux/Mac:**
```bash
export GITHUB_TOKEN=your_github_token_here
```

**Windows:**
```cmd
set GITHUB_TOKEN=your_github_token_here
```

### 2. Run Story Strengthening

**Linux/Mac:**
```bash
cd pos-agent-framework
./strengthen-story.sh louisburroughs/durion-positivity-backend 123
```

**Windows:**
```cmd
cd pos-agent-framework
strengthen-story.bat louisburroughs/durion-positivity-backend 123
```

## Command-Line Options

### Basic Usage

```bash
./strengthen-story.sh <repository> <issue-number> [options]
```

### Arguments

- `<repository>` - Repository in format `owner/repo`
- `<issue-number>` - GitHub issue number to process

### Options

- `--output-dir <path>` - Output directory (default: `./story-output`)
- `--bypass-ssl` - Bypass SSL certificate validation (development only)
- `--help` - Display help message

### Examples

**Process issue #123 from durion-positivity-backend:**
```bash
./strengthen-story.sh louisburroughs/durion-positivity-backend 123
```

**Specify custom output directory:**
```bash
./strengthen-story.sh louisburroughs/durion-positivity-backend 123 --output-dir ./my-output
```

**Development mode with SSL bypass:**
```bash
./strengthen-story.sh louisburroughs/durion-positivity-backend 123 --bypass-ssl
```

## Execution Workflow

The system follows this workflow:

1. **GitHub Connection Test** - Validates GitHub token and API access
2. **Issue Fetching** - Retrieves issue from GitHub API
3. **Issue Preview** - Displays issue details and requests user confirmation
4. **Story Strengthening Pipeline**:
   - Validation (repository, prefix, story type)
   - Parsing (metadata extraction, markdown parsing)
   - Analysis (requirements, actors, ambiguities)
   - Transformation (EARS patterns, Gherkin scenarios)
   - Output Generation (structured markdown document)
5. **Loop Detection** - Monitors for processing loops at each checkpoint
6. **Output Saving** - Saves strengthened story to file

## Output Format

The system generates a structured markdown document with these sections:

1. **Header** - Story metadata and summary
2. **Business Intent** - Core business goal
3. **Actors and Stakeholders** - Identified users and systems
4. **Preconditions and State** - Required conditions
5. **Functional Requirements** - EARS-formatted requirements
6. **Alternate Flows and Error Handling** - Exception scenarios
7. **Business Rules** - Domain constraints
8. **Data Requirements** - Required data elements
9. **Acceptance Criteria** - Gherkin scenarios
10. **Observability Requirements** - Monitoring and logging
11. **Open Questions** - Identified ambiguities
12. **Original Story** - Preserved verbatim

### Output File Naming

Files are saved with this pattern:
```
story_<repository>_issue_<number>_<timestamp>.md
```

Example:
```
story_louisburroughs_durion-positivity-backend_issue_123_20240104_143022.md
```

## Configuration

The system uses these default configuration values:

- **Allowed Repository**: `durion-positivity-backend`
- **Required Issue Prefix**: `[BACKEND] [STORY]`
- **Max Rewrite Iterations**: 2
- **Max Acceptance Criteria**: 25
- **Max Open Questions**: 10
- **Loop Detection**: Enabled

These can be customized by modifying `StoryConfiguration` in the code.

## Stop Phrases

The system may halt processing with stop phrases in these situations:

### Validation Failures
- `STOP: Invalid repository` - Issue not from allowed repository
- `STOP: Missing required prefix` - Issue title missing `[BACKEND] [STORY]`
- `STOP: Not a functional story` - Issue is not a user story

### Loop Detection
- `STOP: Too many rewrite iterations` - Exceeded max iterations (2)
- `STOP: Too many acceptance criteria` - Exceeded threshold (25)
- `STOP: Too many open questions` - Exceeded threshold (10)
- `STOP: Unsafe inference detected` - Requires human expertise (legal, financial, security, regulatory)

### Processing Errors
- `STOP: Issue parsing failed` - Unable to parse issue structure
- `STOP: Loop detected` - Processing loop condition detected

## Troubleshooting

### GitHub API Issues

**Problem**: `GitHub connection test failed`
**Solution**: 
- Verify `GITHUB_TOKEN` is set correctly
- Check token has required scopes (`repo` or `public_repo`)
- Test token at: https://api.github.com/user (with Authorization header)

**Problem**: `Rate limit exceeded`
**Solution**:
- Wait for rate limit reset (system will display reset time)
- Use authenticated requests (token provides 5000 requests/hour)

### SSL Certificate Issues

**Problem**: `SSL certificate validation failed`
**Solution**:
- Use `--bypass-ssl` flag for development environments
- Install proper SSL certificates for production
- Check corporate proxy/firewall settings

### Build Issues

**Problem**: `Maven wrapper not found`
**Solution**:
- Run script from `pos-agent-framework` directory
- Ensure Maven wrapper files exist in project root

**Problem**: `Build failed`
**Solution**:
- Run `./mvnw clean install` to rebuild project
- Check Java version: `java -version` (requires Java 21)
- Review build errors in console output

### Processing Issues

**Problem**: `Issue not found`
**Solution**:
- Verify issue number exists in repository
- Check repository name format: `owner/repo`
- Ensure token has access to repository

**Problem**: `Processing stopped with stop phrase`
**Solution**:
- Review stop phrase reason in output
- Fix issue according to validation requirements
- For loop detection, simplify story or split into multiple issues

## Development Mode

For development and testing, use SSL bypass mode:

```bash
./strengthen-story.sh louisburroughs/durion-positivity-backend 123 --bypass-ssl
```

**Warning**: Only use `--bypass-ssl` in development environments. Never use in production.

## Logging and Observability

The system provides comprehensive logging:

- **Session Tracking** - Unique session ID for each execution
- **Phase Timing** - Duration tracking for each pipeline stage
- **Validation Logging** - Detailed validation results
- **Ambiguity Detection** - All detected ambiguities with context
- **Loop Detection** - Loop condition monitoring
- **Error Logging** - Detailed error context and stack traces

All logs are written to console with structured formatting.

## Testing

Run the complete test suite:

```bash
cd ../..
./mvnw test -pl pos-agent-framework
```

Run specific test classes:

```bash
./mvnw test -pl pos-agent-framework -Dtest=StoryStrengtheningAgentTest
```

Set required environment variable for tests:

```bash
export AGENT_JWT_SECRET=test-secret-key-for-testing
./mvnw test -pl pos-agent-framework
```

## Architecture

The system consists of these main components:

- **StoryStrengtheningSystem** - Main execution framework
- **GitHubApiClient** - GitHub API integration
- **StoryStrengtheningAgent** - Pipeline orchestrator
- **IssueValidator** - Validation logic
- **IssueParser** - Markdown parsing
- **RequirementsAnalyzer** - Requirements analysis
- **RequirementsTransformer** - EARS and Gherkin transformation
- **OutputGenerator** - Structured output generation
- **LoopDetector** - Loop detection and monitoring
- **StoryLogger** - Comprehensive logging

## Requirements Coverage

The system implements all requirements from the specification:

- **REQ-1.x**: Repository and prefix validation
- **REQ-2.x**: Metadata extraction and parsing
- **REQ-3.x**: Output generation and structure
- **REQ-4.x**: Mandatory section ordering
- **REQ-5.x**: Gherkin transformation
- **REQ-6.x**: EARS pattern application
- **REQ-7.x**: ISO/IEC/IEEE 29148 compliance
- **REQ-8.x**: Ambiguity detection and open questions
- **REQ-9.x**: Original content preservation
- **REQ-10.x**: Stop phrase handling
- **REQ-11.x**: Loop detection

## Support

For issues or questions:

1. Check this guide for troubleshooting steps
2. Review test cases for usage examples
3. Check logs for detailed error information
4. Consult specification documents in `.kiro/specs/upgrade-story-quality/`

## License

Part of the Durion Positivity Backend project.
