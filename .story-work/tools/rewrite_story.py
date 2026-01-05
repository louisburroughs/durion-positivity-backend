#!/usr/bin/env python3
"""
Automated story rewriting using LLM API.

Usage:
    python rewrite_story.py <issue_number>
    python rewrite_story.py <start_issue>-<end_issue>
    python rewrite_story.py 14
    python rewrite_story.py 1-10

Environment Variables:
    OPENAI_API_KEY: Your OpenAI API key
    LLM_PROVIDER: 'openai' (default) or 'gemini'
    LLM_MODEL: Model name (default: gpt-4-1106-preview for OpenAI)
"""

import subprocess
import os
import sys
import time
import warnings
from pathlib import Path

# Suppress SSL warnings when bypassing verification
warnings.filterwarnings('ignore', message='Unverified HTTPS request')

# --- CONFIGURATION ---
LLM_PROVIDER = os.environ.get("LLM_PROVIDER", "openai").lower()
OPENAI_API_KEY = os.environ.get("OPENAI_API_KEY")
GEMINI_API_KEY = os.environ.get("GEMINI_API_KEY")
LLM_MODEL = os.environ.get("LLM_MODEL")

# Default models
if not LLM_MODEL:
    if LLM_PROVIDER == "openai":
        LLM_MODEL = "gpt-4-1106-preview"
    elif LLM_PROVIDER == "gemini":
        LLM_MODEL = "gemini-1.5-pro-latest"

WORKSPACE_ROOT = Path(".story-work/work")
REWRITE_PROMPT_PATH = Path(".story-work/REWRITE_PROMPT.md")
MAKE_WORKSPACE_SH = ".story-work/tools/make_rewrite_workspace.sh"
PUBLISH_REWRITE_SH = ".story-work/tools/publish_rewrite.sh"


def run_shell(cmd, check=True):
    """Execute shell command and return stdout."""
    print(f"Running: {cmd}")
    result = subprocess.run(cmd, shell=True, check=check, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"ERROR: {result.stderr}", file=sys.stderr)
        if check:
            raise subprocess.CalledProcessError(result.returncode, cmd)
    return result.stdout.strip()


def call_openai_llm(before_md, rewrite_prompt):
    """Call OpenAI API for story rewrite."""
    try:
        import openai
    except ImportError:
        print("ERROR: openai package not installed. Run: pip install openai", file=sys.stderr)
        sys.exit(1)

    if not OPENAI_API_KEY:
        print("ERROR: OPENAI_API_KEY environment variable not set", file=sys.stderr)
        sys.exit(1)

    openai.api_key = OPENAI_API_KEY
    system_prompt = (
        "You are a senior product and domain architect. "
        "Rewrite the story from before.md into the after.md file using REWRITE_PROMPT.md. "
        "Keep the exact original story under 'Original Story (Unmodified – For Traceability)'. "
        "Follow the format strictly."
    )
    user_content = (
        f"## REWRITE_PROMPT.md\n{rewrite_prompt}\n\n"
        f"## before.md\n{before_md}\n"
    )
    
    print(f"Calling OpenAI API ({LLM_MODEL})...")
    response = openai.ChatCompletion.create(
        model=LLM_MODEL,
        messages=[
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_content}
        ],
        temperature=0.2,
        max_tokens=8192
    )
    return response.choices[0].message.content


def call_gemini_llm(before_md, rewrite_prompt):
    """Call Google Gemini API for story rewrite."""
    try:
        from google import genai
        import httpx
        import ssl
    except ImportError:
        print("ERROR: google-genai package not installed. Run: pip install google-genai", file=sys.stderr)
        sys.exit(1)

    if not GEMINI_API_KEY:
        print("ERROR: GEMINI_API_KEY environment variable not set", file=sys.stderr)
        sys.exit(1)

    # Bypass SSL verification (WARNING: Use only in development/testing)
    # Save originals
    original_client = httpx.Client
    original_async_client = httpx.AsyncClient
    original_ssl_context = ssl.create_default_context
    
    # Create unverified SSL context
    def create_unverified_context(*args, **kwargs):
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        return context
    
    # Patch httpx clients
    class NoVerifyClient(httpx.Client):
        def __init__(self, *args, **kwargs):
            kwargs['verify'] = False
            super().__init__(*args, **kwargs)
    
    class NoVerifyAsyncClient(httpx.AsyncClient):
        def __init__(self, *args, **kwargs):
            kwargs['verify'] = False
            super().__init__(*args, **kwargs)
    
    # Apply patches
    httpx.Client = NoVerifyClient
    httpx.AsyncClient = NoVerifyAsyncClient
    ssl.create_default_context = create_unverified_context
    
    # Set environment variables to disable SSL verification
    old_env = {}
    ssl_env_vars = ['PYTHONHTTPSVERIFY', 'CURL_CA_BUNDLE', 'REQUESTS_CA_BUNDLE']
    for var in ssl_env_vars:
        old_env[var] = os.environ.get(var)
        os.environ[var] = '0'
    
    try:
        # Initialize Gemini client
        client = genai.Client(api_key=GEMINI_API_KEY)
        
        prompt = (
            "You are a senior product and domain architect. "
            "Rewrite the story from before.md into the after.md file using REWRITE_PROMPT.md. "
            "Keep the exact original story under 'Original Story (Unmodified - For Traceability)'. "
            "Follow the format strictly, missing fields will fail later processing.\n\n"
            f"## REWRITE_PROMPT.md\n{rewrite_prompt}\n\n"
            f"## before.md\n{before_md}\n"
        )
        
        print(f"Calling Gemini API ({LLM_MODEL})... [SSL verification disabled]")
        response = client.models.generate_content(
            model=LLM_MODEL,
            contents=prompt
        )
        return response.text
    finally:
        # Restore everything
        httpx.Client = original_client
        httpx.AsyncClient = original_async_client
        ssl.create_default_context = original_ssl_context
        
        # Restore environment variables
        for var, val in old_env.items():
            if val is None:
                os.environ.pop(var, None)
            else:
                os.environ[var] = val


def call_llm(before_md, rewrite_prompt):
    """Route to appropriate LLM provider."""
    if LLM_PROVIDER == "openai":
        return call_openai_llm(before_md, rewrite_prompt)
    elif LLM_PROVIDER == "gemini":
        return call_gemini_llm(before_md, rewrite_prompt)
    else:
        print(f"ERROR: Unknown LLM_PROVIDER: {LLM_PROVIDER}", file=sys.stderr)
        sys.exit(1)


def process_issue(issue_number, rewrite_prompt):
    """Process a single issue: create workspace, rewrite, publish."""
    print(f"\n{'='*60}")
    print(f"Processing Issue #{issue_number}")
    print(f"{'='*60}\n")
    
    try:
        # 1. Create workspace
        workspace_path = run_shell(f"{MAKE_WORKSPACE_SH} {issue_number}")
        workdir = Path(workspace_path)
        
        before_md_path = workdir / "before.md"
        after_md_path = workdir / "after.md"
        
        if not before_md_path.exists():
            print(f"ERROR: before.md not found at {before_md_path}", file=sys.stderr)
            return False
        
        before_md = before_md_path.read_text(encoding="utf-8")
        
        # 2. Call LLM to rewrite
        rewritten_story = call_llm(before_md, rewrite_prompt)
        
        # 3. Write to after.md
        after_md_path.write_text(rewritten_story, encoding="utf-8")
        print(f"✓ Wrote rewritten story to {after_md_path}")
        
        # 4. Publish to GitHub
        run_shell(f"{PUBLISH_REWRITE_SH} -f {after_md_path}")
        print(f"✓ Published Issue #{issue_number} to GitHub")
        
        return True
        
    except Exception as e:
        print(f"✗ ERROR processing Issue #{issue_number}: {e}", file=sys.stderr)
        return False


def parse_issue_range(arg):
    """Parse issue number or range (e.g., '5' or '1-10')."""
    if '-' in arg:
        parts = arg.split('-')
        if len(parts) != 2:
            raise ValueError(f"Invalid range format: {arg}")
        start, end = int(parts[0]), int(parts[1])
        if start > end:
            raise ValueError(f"Invalid range: start ({start}) > end ({end})")
        return list(range(start, end + 1))
    else:
        return [int(arg)]


def main():
    if len(sys.argv) != 2:
        print("Usage: python rewrite_story.py <issue_number>")
        print("       python rewrite_story.py <start_issue>-<end_issue>")
        print("\nExamples:")
        print("  python rewrite_story.py 14")
        print("  python rewrite_story.py 1-10")
        sys.exit(1)
    
    # Parse issue range
    try:
        issue_numbers = parse_issue_range(sys.argv[1])
    except ValueError as e:
        print(f"ERROR: {e}", file=sys.stderr)
        sys.exit(1)
    
    # Load rewrite prompt once
    if not REWRITE_PROMPT_PATH.exists():
        print(f"ERROR: REWRITE_PROMPT.md not found at {REWRITE_PROMPT_PATH}", file=sys.stderr)
        sys.exit(1)
    
    rewrite_prompt = REWRITE_PROMPT_PATH.read_text(encoding="utf-8")
    
    # Process each issue
    print(f"Processing {len(issue_numbers)} issue(s): {issue_numbers}")
    print(f"LLM Provider: {LLM_PROVIDER} ({LLM_MODEL})")
    
    results = []
    for issue_number in issue_numbers:
        success = process_issue(issue_number, rewrite_prompt)
        results.append((issue_number, success))
        
        # Rate limiting: wait between API calls
        if len(issue_numbers) > 1 and issue_number != issue_numbers[-1]:
            print("\nWaiting 2 seconds before next issue...")
            time.sleep(2)
    
    # Summary
    print(f"\n{'='*60}")
    print("SUMMARY")
    print(f"{'='*60}")
    successful = [n for n, s in results if s]
    failed = [n for n, s in results if not s]
    
    print(f"✓ Successful: {len(successful)}/{len(results)}")
    if successful:
        print(f"  Issues: {successful}")
    
    if failed:
        print(f"✗ Failed: {len(failed)}/{len(results)}")
        print(f"  Issues: {failed}")
        sys.exit(1)


if __name__ == "__main__":
    main()
