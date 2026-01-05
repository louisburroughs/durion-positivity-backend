# Clarification Request

> **Purpose:** Resolve missing information needed to finalize the originating user story for development and testing.  
> **Do not implement here.** Provide decisions/answers; the Story Authoring Agent will update the story.

## Origin
- **Origin Repository:** {{originRepo}}
- **Origin Story Issue:** {{originIssueUrl}}
- **Origin Story Title:** {{originIssueTitle}}
- **Requested By Agent:** {{requestingAgent}} (runId: {{runId}})
- **Date:** {{isoDate}}

## Domain Routing
- **Domain:** {{domainLabel}}  
- **Clarification Type:** {{clarificationLabel}}

## Summary
{{summary}}

## Questions (Answer Required)
{{#each questions}}
{{index}}) {{this}}
{{/each}}

## Why This Matters
{{whyItMatters}}

## Impact If Unanswered
{{impact}}

## Relevant Context (From Story)
> Keep this short—only the minimum necessary excerpts or paraphrase.

- **Story Section:** {{storySectionRef}}
- **Context Notes:** {{contextNotes}}

## Options Considered (If Any)
{{#if options}}
{{options}}
{{else}}
N/A
{{/if}}

## Resolution Acceptance Criteria
A response is considered complete when it includes:
{{#each resolutionCriteria}}
- {{this}}
{{/each}}

## Links
- Origin Story: {{originIssueUrl}}
{{#if relatedIssues}}
- Related Issues:
{{#each relatedIssues}}
  - {{this}}
{{/each}}
{{/if}}

---
### Agent Instructions (Do Not Edit)
- When this issue is answered, the Story Authoring Agent MUST:
  - Update the origin story with the decision(s)
  - Remove `blocked:clarification` from the origin story
  - Add `status:needs-review` (or `status:ready-for-dev` if no open questions remain)
- If answers conflict with domain policy, create a new issue: `[CLARIFICATION] Domain conflict` and label `blocked:domain-conflict`
