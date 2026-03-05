# Clock Injection Remediation Baseline

Generated on 2026-03-04 (America/New_York).

## Pre-remediation snapshot

- Pattern scanned: `\\b(Instant\\.now|LocalDateTime\\.now)\\(\\s*\\)`
- Scope: `src/main` + `src/test`
- Matches: `793`
- Files: `247`

## Post-remediation snapshot

- Pattern scanned: `\\b(Instant\\.now|LocalDateTime\\.now)\\(\\s*\\)`
- Scope: `src/main` + `src/test`
- Matches: `0`
- Files: `0`

## Verification commands

```bash
rg -n "\\b(Instant\\.now|LocalDateTime\\.now)\\(\\s*\\)" --glob "**/src/main/**" --glob "**/src/test/**" . | wc -l
rg -l "\\b(Instant\\.now|LocalDateTime\\.now)\\(\\s*\\)" --glob "**/src/main/**" --glob "**/src/test/**" . | wc -l
```
