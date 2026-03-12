## Purpose

Runbook for handling confirmation token mismatches or cross-user confirmation attempts resulting in HTTP 403.

## Symptoms

- Requests failing with HTTP 403 at confirmation endpoints
- Logs show `confirmation token mismatch`, `user mismatch`, or `invalid confirmation` entries
- Potential security event if cross-user confirmations are attempted

## Detection

- Application logs with confirmation validation failures and correlation IDs
- `nlt.error.count` with `confirmation=403` tag rising
- Possible security alerts for suspicious cross-user attempts

## Immediate Actions

1. Collect request IDs, tokens (if available) and user IDs involved; redact sensitive tokens before sharing.
2. Verify if token expiration is expected behavior or indicates misrouted confirmation.
3. If suspicious activity detected, block offending sessions and escalate to Security.
4. Inform user-facing support teams with safe guidance to re-initiate confirmation flow.

## Escalation

- Escalate to Security team for potential account compromise or malicious activity.
- Provide redacted evidence, timestamps, and affected user IDs.

## Rollback / Recovery

- If a code change introduced a token validation regression, revert the change and reissue confirmation tokens as needed.
- Offer manual confirmation processes for high-priority cases following security review.

## Post-Incident Notes

- Record whether issue was user error, race-condition, or security incident.
- Update confirmation flow documentation and add tests to prevent regressions.
