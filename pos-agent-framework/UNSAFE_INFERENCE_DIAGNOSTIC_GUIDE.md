# Unsafe Inference Detection - Diagnostic Guide

## Overview

The Story Strengthening Agent includes enhanced diagnostic reporting for unsafe inference detection. When the system encounters keywords related to sensitive domains (security, legal, financial, regulatory), it now provides detailed information to help you understand what triggered the detection and how to fix it.

## What You'll See

When your story triggers the unsafe inference detection, you'll now get a detailed diagnostic report like this:

```
🛑 STOP: Unsafe inference required

🔒 Security keywords detected: [authentication, password, credential, token]
💰 Financial keywords detected: [payment, transaction, billing]

Action: Please reword the issue title/description to remove these keywords.
Reason: AI agents cannot safely infer these domain-specific rules without explicit human guidance.
```

## Keyword Categories

### 🔒 Security Keywords
The system blocks stories that discuss authentication, authorization, encryption, credentials, or vulnerabilities:
- `security`, `authentication`, `authorization`, `encryption`, `credential`
- `password`, `token`, `certificate`, `vulnerability`, `vulnerabilities`
- `threat`, `attack`

### 💰 Financial Keywords
The system blocks stories that involve payments, transactions, or financial operations:
- `financial`, `payment`, `transaction`, `money`, `currency`, `tax`
- `accounting`, `invoice`, `billing`, `pricing`, `revenue`, `cost`

### 📋 Legal Keywords
The system blocks stories that require legal interpretation:
- `legal`, `law`, `statute`, `contract`, `liability`, `jurisdiction`
- `litigation`, `lawsuit`, `attorney`, `counsel`

### ⚖️ Regulatory Keywords
The system blocks stories that discuss compliance or regulatory requirements:
- `regulatory`, `compliance`, `audit`, `policy`, `governance`, `standard`
- `certification`, `accreditation`, `mandate`, `requirement`, `regulation`
- `gdpr`, `hipaa`, `ccpa`, `sox`, `pci`

## How to Fix

When you see this error, follow these steps:

### 1. Identify the Triggering Keywords
The diagnostic report lists exactly which keywords were found. For example, if you see:
```
🔒 Security keywords detected: [authentication, password]
```

### 2. Reword Your Issue
Remove or replace the problematic keywords:

**Before (FAILS):**
```
[BACKEND] [STORY] Implement user authentication with password encryption
```

**After (PASSES):**
```
[BACKEND] [STORY] Implement user login feature
```

### 3. Document Security Requirements Separately
Move sensitive requirements to a separate section or issue:

```markdown
# Functional Story
[BACKEND] [STORY] Implement user login feature
As a user, I want to log in to the system...

# Security Requirements (Separate Issue)
[SECURITY] Implement password encryption for stored credentials
[SECURITY] Enforce SSL/TLS for all authentication endpoints
```

## Examples

### Example 1: Security Keywords
**Problem:**
```
[BACKEND] [STORY] Add OAuth token validation
```
**Keywords Detected:** `token`

**Solution:**
```
[BACKEND] [STORY] Add user identity verification
```

### Example 2: Financial Keywords
**Problem:**
```
[BACKEND] [STORY] Process refund transactions
```
**Keywords Detected:** `refund`, `transaction`

**Solution:**
```
[BACKEND] [STORY] Handle payment reversals
```

### Example 3: Multiple Categories
**Problem:**
```
[BACKEND] [STORY] Implement compliance audit trail with password protection
```
**Keywords Detected:** `compliance`, `audit`, `password`

**Solution - Split into two:**
1. Functional story:
   ```
   [BACKEND] [STORY] Implement activity logging system
   ```

2. Security/Compliance issue (separate):
   ```
   [COMPLIANCE] Implement password-protected audit trail
   ```

## When This Applies

This detection applies to:
- Issue titles
- Issue descriptions
- Acceptance criteria
- Any text in the issue body

## Why This Exists

The Story Strengthening Agent uses AI to enhance functional requirements. AI agents should **not** make assumptions about:
- How to secure systems
- How to handle financial transactions
- How to ensure legal compliance
- How to meet regulatory requirements

These decisions must be made by **human domain experts** and then documented explicitly in the requirements.

## Advanced: Disabling Detection

To process stories with these keywords, you would need to:
1. Work with security/compliance/legal teams to pre-document all assumptions
2. Create a separate "Requirements" document outside the story strengthening system
3. Have humans review and approve all sensitive domain decisions

The system is designed to enforce this workflow for safety and compliance.

## Questions?

If you're unsure how to reword your issue, consider:
- **Focus on the user action**: What does the user do? (not how the system secures it)
- **Separate concerns**: Split functional requirements from security/compliance requirements
- **Use domain teams**: Let security/legal/finance teams document their requirements separately

## Test Your Story

You can test if your story will pass by checking if it contains any of the keywords listed above. The diagnostic report will tell you exactly which ones to remove.
