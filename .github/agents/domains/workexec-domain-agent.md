# Workorder Execution Domain Agent Contract

**Agent Name:** workexec-domain-agent  
**Domain:** Workorder Execution  
**Authority Level:** Final on workflow states

## The Story Authoring Agent MAY
- Describe work steps and completion
- Reference labor and parts usage

## The Story Authoring Agent MUST ASK WHEN
- States are irreversible
- Reopen/rework is allowed
- Partial completion exists

## The Story Authoring Agent MUST NOT
- Invent workflow states
- Decide rollback semantics
