# Clarification Issue #227 - Documentation

This directory contains tracking and status documentation for clarification issue #227.

## Files in This Directory

### Status and Tracking
- `clarification-227-summary.md` - **START HERE** - Quick overview of the situation
- `clarification-227-status.md` - Detailed status report with full context
- `clarification-227-metadata.json` - Machine-readable tracking data

### Previous Work (Reference)
- `clarification-resolution-metadata.json` - Example from resolved issue #194
- `issue-194-update-summary.md` - Example of completed integration
- `apply-clarification-resolution.sh` - Script template for integration

## Current Situation

**Status:** 🔴 BLOCKED - Awaiting Business Decision

Clarification issue #227 is waiting for a business decision about soft vs hard allocation logic. No answers have been provided yet.

## What You Need to Do

If you're a **business owner or product manager**:
1. Read `clarification-227-summary.md` for the quick overview
2. Go to https://github.com/louisburroughs/durion-positivity-backend/issues/227
3. Answer the question in the comments
4. The Story Authoring Agent will handle the rest

If you're a **developer or technical team member**:
- Wait for the business decision to be made
- Origin story #29 cannot proceed until then
- Do NOT guess or implement based on assumptions

## For Automation/Agents

The `clarification-227-metadata.json` file contains:
- Structured question data
- Tracking timestamps
- Next steps workflow
- Compliance validation

Use this for process automation and resumption.

## Why This Process Exists

The Story Authoring Agent follows a strict protocol:
- Never guess business logic
- Always ask for clarification when information is missing
- Block stories until decisions are made
- Maintain full traceability

This ensures stories are implementation-ready without unsafe assumptions.

---

**Learn More:**
- Story Authoring Agent contract: `.github/agents/story-authoring-agent.md`
- Clarification protocol: Section 7 of story-authoring-agent.md
