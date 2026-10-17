#!/usr/bin/env python3
"""Post an OpenCode code review to a pull request as a GitHub review.

Reads the findings file OpenCode wrote (see .config/opencode/review-prompt.md
for the schema), keeps the inline comments that land on lines present in the
PR diff, and submits one GitHub pull request review with those comments. Any
finding that does not sit on a diff line is listed in the review body instead,
so nothing is lost and the GitHub API never rejects the review for an
out-of-diff position.

The review is always submitted as a COMMENT review: it never approves or
requests changes, so it can not block a merge on its own.

Environment:
  GITHUB_TOKEN        token with pull-requests: write
  GITHUB_REPOSITORY   owner/repo
  PR_NUMBER           pull request number
  HEAD_SHA            commit the review is attached to
  BASE_REF            base branch name (diff is computed as origin/BASE...HEAD)
  REVIEW_FILE         path of the JSON findings file
  RUN_URL             optional link to the workflow run, shown in the footer

Usage:
  post-opencode-review.py            submit the review
  post-opencode-review.py --dry-run  print the request payload, submit nothing
"""

import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request

MARKER = "<!-- opencode-review -->"
MAX_INLINE_COMMENTS = 25
HUNK_RE = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")


def fail(message: str) -> None:
    print(f"::error::{message}", file=sys.stderr)
    sys.exit(1)


def env(name: str, required: bool = True) -> str:
    value = os.environ.get(name, "").strip()
    if required and not value:
        fail(f"{name} is not set")
    return value


def diff_lines(base_ref: str) -> dict[str, set[int]]:
    """Return {path: {right-side line numbers present in the PR diff}}."""
    out = subprocess.run(
        ["git", "diff", "--unified=3", "--no-color", f"origin/{base_ref}...HEAD"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    lines: dict[str, set[int]] = {}
    path = None
    right = 0
    for raw in out.splitlines():
        if raw.startswith("+++ "):
            target = raw[4:]
            path = None if target == "/dev/null" else target[2:] if target.startswith("b/") else target
            if path is not None:
                lines.setdefault(path, set())
            continue
        if raw.startswith("--- ") or raw.startswith("diff --git") or raw.startswith("index "):
            continue
        hunk = HUNK_RE.match(raw)
        if hunk:
            right = int(hunk.group(1))
            continue
        if path is None:
            continue
        if raw.startswith("+"):
            lines[path].add(right)
            right += 1
        elif raw.startswith("-"):
            continue
        elif raw.startswith("\\"):
            continue
        else:
            lines[path].add(right)
            right += 1
    return lines


def load_findings(review_file: str) -> dict:
    try:
        with open(review_file, encoding="utf-8") as handle:
            data = json.load(handle)
    except FileNotFoundError:
        fail(f"OpenCode did not write {review_file}; nothing to post")
    except json.JSONDecodeError as exc:
        fail(f"{review_file} is not valid JSON: {exc}")
    if not isinstance(data, dict):
        fail(f"{review_file} must contain a JSON object")
    if not isinstance(data.get("summary", ""), str):
        fail("'summary' must be a string")
    comments = data.get("comments", [])
    if not isinstance(comments, list):
        fail("'comments' must be a list")
    return data


def split_comments(comments: list, valid: dict[str, set[int]]) -> tuple[list[dict], list[str]]:
    inline: list[dict] = []
    overflow: list[str] = []
    for item in comments:
        if not isinstance(item, dict):
            continue
        path = item.get("path")
        line = item.get("line")
        body = item.get("body")
        if not (isinstance(path, str) and isinstance(body, str) and body.strip()):
            continue
        severity = item.get("severity")
        adr = item.get("adr")
        prefix = f"**{severity.strip()}**: " if isinstance(severity, str) and severity.strip() else ""
        suffix = f" ({adr.strip()})" if isinstance(adr, str) and adr.strip() and adr.strip().upper() != "N/A" else ""
        text = f"{prefix}{body.strip()}{suffix}"
        if isinstance(line, bool) or not isinstance(line, int):
            line = None
        if line is not None and line in valid.get(path, set()) and len(inline) < MAX_INLINE_COMMENTS:
            inline.append({"path": path, "line": line, "side": "RIGHT", "body": text})
        else:
            where = f"`{path}`" + (f" line {line}" if line is not None else "")
            overflow.append(f"- {where}: {text}")
    return inline, overflow


def build_body(summary: str, overflow: list[str], inline_count: int, run_url: str) -> str:
    parts = [MARKER, "## OpenCode review", ""]
    parts.append(summary.strip() or "No summary provided.")
    if overflow:
        parts += ["", "### Findings outside the diff", ""]
        parts += overflow
    if inline_count:
        parts += ["", f"{inline_count} inline comment(s) posted on the changed lines."]
    parts += ["", "---", "_Automated review by OpenCode" + (f" ([run]({run_url}))" if run_url else "") + "._"]
    return "\n".join(parts)


def post_review(repo: str, pr_number: str, token: str, payload: dict) -> None:
    request = urllib.request.Request(
        f"https://api.github.com/repos/{repo}/pulls/{pr_number}/reviews",
        data=json.dumps(payload).encode("utf-8"),
        method="POST",
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            print(f"Posted review: HTTP {response.status}")
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {exc.code}: {detail}") from exc


def main() -> None:
    dry_run = "--dry-run" in sys.argv[1:]
    token = env("GITHUB_TOKEN", required=not dry_run)
    repo = env("GITHUB_REPOSITORY", required=not dry_run)
    pr_number = env("PR_NUMBER", required=not dry_run)
    head_sha = env("HEAD_SHA", required=not dry_run)
    base_ref = env("BASE_REF")
    review_file = env("REVIEW_FILE")
    run_url = env("RUN_URL", required=False)

    findings = load_findings(review_file)
    valid = diff_lines(base_ref)
    inline, overflow = split_comments(findings.get("comments", []), valid)
    body = build_body(findings.get("summary", ""), overflow, len(inline), run_url)
    payload = {"commit_id": head_sha, "event": "COMMENT", "body": body, "comments": inline}

    if dry_run:
        print(json.dumps(payload, indent=2))
        return

    try:
        post_review(repo, pr_number, token, payload)
    except RuntimeError as exc:
        if not inline:
            fail(str(exc))
        print(f"::warning::Review with inline comments was rejected ({exc}); retrying with comments folded into the body")
        folded = overflow + [f"- `{c['path']}` line {c['line']}: {c['body']}" for c in inline]
        payload = {
            "commit_id": head_sha,
            "event": "COMMENT",
            "body": build_body(findings.get("summary", ""), folded, 0, run_url),
        }
        try:
            post_review(repo, pr_number, token, payload)
        except RuntimeError as exc2:
            fail(str(exc2))


if __name__ == "__main__":
    main()
