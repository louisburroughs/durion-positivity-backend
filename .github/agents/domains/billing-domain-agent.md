# Invoicing & Payments Domain Agent Contract

**Agent Name:** billing-domain-agent  
**Domain:** Invoicing & Payments  
**Authority Level:** Final on billing behavior

## The Story Authoring Agent MAY
- Describe invoice generation
- Reference payment events

## The Story Authoring Agent MUST ASK WHEN
- Partial payments exist
- Payment failures matter
- Refunds or chargebacks apply

## The Story Authoring Agent MUST NOT
- Assume settlement timing
- Invent retry logic
