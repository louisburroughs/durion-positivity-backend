# Clarification Resolver

Automatically resolves `blocked:clarification` GitHub issues by using ChatGPT to answer open questions.

## Overview

This zsh script:
1. Queries the repository for issues labeled `blocked:clarification`
2. Extracts open questions from each issue
3. Uses OpenAI's ChatGPT API to generate answers
4. Posts the answers as comments on the original GitHub issues
5. Includes comprehensive error handling and logging

## Prerequisites

- **gh** (GitHub CLI): https://cli.github.com/
- **curl**: For HTTP requests to OpenAI API
- **jq**: For JSON parsing
- **zsh**: Shell interpreter

Install dependencies on macOS:
```bash
brew install gh curl jq
```

## Setup

### 1. Authenticate with GitHub

```bash
gh auth login
```

### 2. Set OpenAI API Key

Export your OpenAI API key:

```bash
export OPENAI_API_KEY=sk_your_actual_api_key_here
```

For persistent setup, add to your shell profile (`~/.zshrc`, `~/.bashrc`, etc.):

```bash
export OPENAI_API_KEY=sk_...
```

## Usage

### Basic Usage (Dry Run - Recommended First)

```bash
cd /path/to/durion-positivity-backend/.story-work
export OPENAI_API_KEY=sk_...
./clarification-resolver.sh --dry-run
```

This will show what would be done without posting any comments.

### Verbose Dry Run (See Full Prompts)

```bash
./clarification-resolver.sh --dry-run --verbose
```

### Execute for Real

Once satisfied with dry-run output:

```bash
./clarification-resolver.sh
```

### Command-Line Options

```
--dry-run       Print what would be done without posting comments
--verbose       Enable verbose output (shows ChatGPT prompts)
--help          Show help message
```

## Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `OPENAI_API_KEY` | Yes | N/A | Your OpenAI API key (must start with `sk-`) |
| `OPENAI_MODEL` | No | `gpt-4` | ChatGPT model to use |
| `REPO` | No | `louisburroughs/durion-positivity-backend` | GitHub repository |

## How It Works

1. **Discovery Phase**: Queries GitHub for all open issues with the `blocked:clarification` label
2. **Analysis Phase**: For each issue:
   - Extracts the "Open Questions" section (or uses full body if no dedicated section)
   - Constructs a detailed prompt for ChatGPT
3. **Generation Phase**: Calls OpenAI's chat completion API with the prompt
4. **Posting Phase**: Posts the response as a GitHub comment with metadata
5. **Rate Limiting**: Waits 2 seconds between API calls to respect rate limits

## Output & Logging

### Console Output
- Color-coded messages (INFO, SUCCESS, WARN, ERROR)
- Real-time progress as issues are processed
- Summary of success/error counts

### Log File
All activity is logged to `.story-work/clarification-resolver.log` for audit trails.

Example log entry:
```
[2026-01-14 15:42:30] [INFO] Processing issue #15: [BACKEND] [STORY] Workexec: Retrieve and Display Estimates...
[2026-01-14 15:42:35] [SUCCESS] Comment posted to issue #15
```

## Error Handling

The script includes robust error handling for:

- **Missing dependencies**: Checks for `gh`, `curl`, `jq` at startup
- **GitHub authentication**: Verifies `gh auth status` before proceeding
- **API key validation**: Ensures `OPENAI_API_KEY` is set and formatted correctly
- **Network failures**: Reports HTTP status codes and API errors
- **Malformed responses**: Validates JSON parsing with helpful error messages
- **Rate limiting**: Gracefully handles OpenAI API rate limits
- **GitHub API failures**: Retryable errors are logged with context

All errors are:
1. Logged to console with color coding
2. Written to the log file with timestamps
3. Reported with actionable remediation steps

## Examples

### Resolve all blocked issues with default model

```bash
export OPENAI_API_KEY=sk_...
./.story-work/clarification-resolver.sh
```

### Test with gpt-3.5-turbo (faster, cheaper)

```bash
export OPENAI_API_KEY=sk_...
export OPENAI_MODEL=gpt-3.5-turbo
./.story-work/clarification-resolver.sh --dry-run --verbose
```

### Inspect what would happen (verbose dry run)

```bash
export OPENAI_API_KEY=sk_...
./.story-work/clarification-resolver.sh --dry-run --verbose | tee my-run.log
```

Then check the GitHub issue manually to confirm the responses are good before:

```bash
./.story-work/clarification-resolver.sh
```

## Cost Estimates

Using GPT-4:
- Input: ~1,000 tokens per issue (depending on question complexity)
- Output: ~500 tokens per issue (depends on response length)
- Cost: ~$0.03-0.05 per issue (~10 issues ≈ $0.30-0.50)

Using GPT-3.5-turbo (recommended for cost-sensitive use):
- ~$0.002-0.003 per issue (~10 issues ≈ $0.02-0.03)

## Troubleshooting

### "GitHub CLI not authenticated"
```bash
gh auth login
# Follow the interactive prompts
```

### "OPENAI_API_KEY not set"
```bash
export OPENAI_API_KEY=sk_your_key_here
# Or add to ~/.zshrc for persistence
```

### "curl: command not found"
```bash
brew install curl
# or: apt-get install curl (on Linux)
```

### "ChatGPT API request failed (HTTP 401)"
Check that your API key is valid and has sufficient credits:
https://platform.openai.com/account/api-keys

### "ChatGPT API request failed (HTTP 429)"
Rate limited by OpenAI. The script waits 2 seconds between calls; you can increase this by editing the `sleep 2` line in the script.

### "jq: command not found"
```bash
brew install jq
# or: apt-get install jq (on Linux)
```

## Security Notes

⚠️ **Never commit your API key to version control.**

- Use environment variables only
- Consider using a `.env` file (which is `.gitignore`d) for local development
- Rotate API keys regularly in the OpenAI dashboard
- Audit API usage: https://platform.openai.com/account/usage/overview

## Future Enhancements

- [ ] Support for custom ChatGPT system prompts
- [ ] Filtering by issue number range
- [ ] Retry logic for transient failures
- [ ] Batch processing with progress bar
- [ ] Webhook integration for automatic runs
- [ ] Support for other LLM providers (Anthropic, Ollama, etc.)

## License

Part of the Durion project.
