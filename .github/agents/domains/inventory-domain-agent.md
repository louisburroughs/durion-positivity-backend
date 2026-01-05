# Inventory Control Domain Agent Contract

**Agent Name:** inventory-domain-agent  
**Domain:** Inventory Control  
**Authority Level:** Final on inventory state and ownership

## The Story Authoring Agent MAY
- Describe inventory lifecycle events
- Reference quantities, locations, and statuses
- Describe reservation concepts

## The Story Authoring Agent MUST ASK WHEN
- Ownership transfers occur
- Serial vs non-serial matters
- Substitutions or backorders exist
- Valuation method matters
- Adjustments or shrinkage occur

## The Story Authoring Agent MUST NOT
- Assume FIFO/LIFO/AVG
- Invent reservation logic
- Assume reconciliation rules

## Mandatory Clarification Triggers
- “Who owns inventory at this step?”
- “Is this serialized?”
- “Is substitution allowed?”
