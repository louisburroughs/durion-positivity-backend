# Marketing Campaigns Guide — Campaigns, Audiences, Message Templates and Results

## Purpose

RAG id: `marketing.campaigns`  
RAG scope: `marketing`  
Required permissions: `marketing:campaign:view`, `marketing:template:view`, `marketing:stats:view`  
Audience: marketing staff, account managers and administrators.  
This document is reference context only and grants no access; access is enforced by permission codes at request time.

This guide explains marketing on the Durion Positivity platform (`pos-marketing`): what a campaign is, how its audience
is chosen and filtered, how email and SMS message templates work, how a campaign moves from draft to sent, and how to
read its results. It answers questions such as "Which campaigns are active?", "Launch the spring alignment campaign",
"Why did only half the audience get the message?" or "Which tokens can I use in an SMS?".

---

## Concepts

Email marketing is the sending of a commercial message, typically to a group of people, by email (see Sources [1]); the
platform also supports SMS. The group is usually a **segment**: a meaningful sub-group of current or potential
customers, defined differently for business customers (company type, industry, location) and for consumers
(demographics, behaviour) (see Sources [2]).

| Term | On the platform |
| --- | --- |
| **Campaign** | One outreach: a unique code (for example `SPRING-FLEET-2026`), a name and description, an audience type, the channels it uses, a bound segment, the templates for each channel, an optional promotion offer and catalog focus, an optional active window, and a schedule. |
| **Audience type** | `COMMERCIAL` (business accounts, messaged through the account's designated contact) or `INDIVIDUAL` (consumers, messaged directly). Fixed when the campaign is created. |
| **Channel** | `EMAIL` or `SMS`. A campaign uses one or both. |
| **Segment** | The customer group a campaign targets. Segments are defined and resolved by the customer service (`pos-customer`, CRM permissions `crm:segment:view`, `crm:segment:manage`, `crm:segment:resolve`); marketing only binds a campaign to one. |
| **Audience snapshot** | The segment's membership as last resolved for this campaign. Marketing asks the customer service to resolve it and stores the answer, so counts reflect the time of the snapshot, not live CRM data. |
| **Message template** | The wording for one channel and one audience type, with `{{token}}` placeholders. |
| **Program** | A `campaignProgramId` shared by several campaigns, typically the commercial and individual arms of one initiative. It has no record of its own. |
| **Promotion offer** | An optional offer from pricing (`pos-price`) the campaign promotes. |
| **Catalog focus** | An optional reference to what the campaign is about, written `kind:value` with kind `product`, `sku`, `service` or `category` (for example `service:alignment`). |

There is no separate audience-builder in marketing: the audience is always the bound CRM segment, filtered at preview
and send time by consent and suppression.

---

## Campaign lifecycle

| Status | Meaning | Can move to |
| --- | --- | --- |
| `DRAFT` | Being defined. The only status in which the definition can be edited. | `SCHEDULED`, `CANCELLED` |
| `SCHEDULED` | Checked and ready; dispatch is allowed. | `SENDING`, `PAUSED`, `CANCELLED`, `DRAFT` |
| `SENDING` | Messages are queued and being delivered. | `SENT`, `PAUSED`, `CANCELLED` |
| `PAUSED` | Delivery halted; queued messages wait. | `SCHEDULED`, `SENDING`, `CANCELLED` |
| `SENT` | Every queued message has been processed (set automatically). | `CLOSED` |
| `CANCELLED` | Abandoned for good. Terminal. | none |
| `CLOSED` | Finished and archived. Terminal. No operation closes a campaign today. | none |

"Active campaigns" usually means those `SCHEDULED`, `SENDING` or `PAUSED`; the campaign list filters by one status at a
time (and by program).

### From draft to sent

1. **Create** (`marketing:campaign:create`): code, name, audience type and at least one channel. The campaign starts as
   `DRAFT`; nobody is contacted. A code already in use is refused (409).
2. **Edit** (`marketing:campaign:edit`): replace the definition while it is still `DRAFT` (bind the segment, attach
   templates, set the offer, window and schedule). The audience type cannot change.
3. **Preview the audience** (`marketing:campaign:view`): see the reach per channel before going further (below).
4. **Schedule** (`marketing:campaign:schedule`): checks readiness and moves the campaign to `SCHEDULED`. It needs at
   least one channel; a bound segment that is known, active and of the same audience type; a template for every
   channel; any promotion offer `ACTIVE` in pricing with today inside its dates; and a well-formed catalog focus. Every
   problem is reported at once (422).
5. **Dispatch** (`marketing:campaign:send`): queues one message per recipient and channel from the audience snapshot,
   moves a `SCHEDULED` campaign to `SENDING`, and returns how many were queued. Repeating it never sends twice, because
   each recipient and channel pair is unique per campaign. If no snapshot has arrived yet it queues nothing, asks the
   customer service for one, and reports `queued: 0` so it can be retried.
6. **Delivery** runs in the background. When nothing is left queued the campaign becomes `SENT` on its own.

`scheduleType` (`IMMEDIATE` by default, or `SCHEDULED` with a `scheduledAt` time) and `scheduledAt` are stored and
published with the campaign, but nothing on the platform dispatches automatically at that time: delivery starts when
the dispatch action is called.

**Pause, resume and cancel** (`marketing:campaign:manage`). Pausing a `SCHEDULED` or `SENDING` campaign stops the
background delivery; queued messages stay queued. Resuming returns it to `SCHEDULED`: the background delivery picks
the queued messages up again, and dispatching once more moves it back to `SENDING` without re-sending anyone already
sent. Cancelling is permanent and does not recall messages already delivered. There
is no delete operation for campaigns.

**Launching is high risk.** Dispatch sends messages outside the shop to real customers and cannot be undone, so the
assistant treats it as a high-risk action (ADR-0068), states the campaign, channels and expected reach, and asks for
explicit confirmation first.

---

## Audience, consent and suppression

Commercial messaging law in the platform's markets requires consent before sending and a working way to opt out:
Canada's anti-spam law lets marketers email only those who have opted in and requires an unsubscribe mechanism
(see Sources [3]); the US CAN-SPAM Act requires a visible, operable unsubscribe mechanism and that opt-outs be honoured
(see Sources [4]). The platform enforces its side of this from replicated CRM data:

- **Suppression first.** A party suppressed on a channel is never sent to on that channel.
- **Consent must be known and fresh.** A party with no consent decision on record, or whose decision is older than the
  configured maximum age (24 hours by default), is refused. The platform fails closed: a delayed message can be sent
  later, an unwanted one cannot be taken back.
- The same check runs at preview and again at send time, so a customer who opts out after the preview is still
  skipped.

**Audience preview** (`marketing:campaign:view`) reports, per channel: the number of segment members in the snapshot
(and whether the CRM capped it), how many are eligible after consent, staleness and suppression, whether a template is
attached, a bounded sample of individual decisions with their reason code (for example `SUPPRESSED`,
`NO_CONSENT_DATA`, `CONSENT_STALE`), and the readiness problems that would block scheduling. Samples carry party ids
and decision codes only, never names or contact details. While the campaign is `DRAFT`, `SCHEDULED` or `PAUSED`, a
preview also asks for a fresher snapshot. A campaign with no segment cannot be previewed (422).

---

## Message templates

| Rule | Detail |
| --- | --- |
| One channel, one audience type | Both are fixed when the template is created; to change either, make a new template. |
| Name | Unique, compared case-insensitively. |
| Subject | Required for `EMAIL`; not allowed for `SMS`. |
| SMS length | At most 320 characters. |
| Tokens | Checked when the template is saved; an unknown token, or one that does not apply to the audience type, is refused (422). |
| Delete | Only when no campaign uses the template as its email or SMS template; deletion is permanent. |

Available tokens: `accountName` and `accountTier` (commercial only); `vehicleMake`, `vehicleModel` and `vehicleYear`
(individual only); `contactFirstName`, `customerNumber`, `campaignCode`, `offerCode`, `shopName` and `unsubscribeUrl`
(both). Today only `campaignCode` is filled in at send time; the other tokens render empty until recipient data is
supplied by the platform sender, so a template should read well without them. Editing a template changes the wording
of campaigns that use it from their next dispatch.

| Action | Permission |
| --- | --- |
| List or read templates | `marketing:template:view` |
| Create, edit or delete templates | `marketing:template:manage` |

---

## Sends and results

Each queued message is a **send record** for one recipient and channel, with a status: `PENDING`, `SENT`, `DELIVERED`,
`BOUNCED`, `COMPLAINED`, `SUPPRESSED` (refused by consent, account rules or suppression at send time) or `FAILED`. The
send list (`marketing:campaign:view`) shows these one by one, with the failure reason and attempts.

**Campaign stats** (`marketing:stats:view`) give, per channel, the delivery funnel (targeted, suppressed, sent,
delivered, bounced, complained, failed; "sent" counts delivered, bounced and complained messages too) and the number
of promotion redemptions and discount value attributed to the campaign code. **Program stats** put the stats of every
campaign in a program side by side.

**Delivery transport.** By default the platform runs a stand-in transport that records what would have been sent and
contacts no one; outcomes beyond acceptance (delivered, bounced, complained) arrive only when a deployment uses the
shared platform sender. Ask an administrator which one is configured before promising customers a message.

---

## Permissions at a glance

| Area | Permission |
| --- | --- |
| View campaigns, audience previews and send records | `marketing:campaign:view` |
| Create a campaign | `marketing:campaign:create` |
| Edit a draft campaign | `marketing:campaign:edit` |
| Schedule a campaign | `marketing:campaign:schedule` |
| Dispatch (send) a campaign | `marketing:campaign:send` |
| Pause, resume or cancel a campaign | `marketing:campaign:manage` |
| View templates / manage templates | `marketing:template:view` / `marketing:template:manage` |
| View campaign and program stats | `marketing:stats:view` |
| Define or resolve customer segments (CRM) | `crm:segment:view`, `crm:segment:manage`, `crm:segment:resolve` |
| Consent and suppression records (CRM) | `crm:consent:view`, `crm:consent:manage`, `crm:suppression:view`, `crm:suppression:manage` |

Which roles hold these codes is tenant configuration: ask a security administrator, or see the role-permission matrix
(`security.role-permission-matrix`).

---

## Sources

Platform sources:

- `pos-marketing/src/main/java/com/positivity/marketing/internal/controller/CampaignController.java`,
  `MessageTemplateController.java`, `CampaignStatsController.java`
- `pos-marketing/src/main/java/com/positivity/marketing/internal/enums/CampaignStatus.java`, `AudienceType.java`,
  `CampaignChannel.java`, `ScheduleType.java`, `SendStatus.java`; `internal/domain/TemplateToken.java`,
  `CatalogFocusRef.java`; `internal/dto/CampaignResponse.java`, `AudiencePreviewResponse.java`
- `pos-marketing/src/main/java/com/positivity/marketing/internal/service/AudienceEligibilityService.java`,
  `CampaignSendWorker.java`, `CampaignSendServiceImpl.java`, `SegmentResolveRequester.java`,
  `LoggingMessageChannel.java`; `internal/client/PlatformSenderClient.java`
- `pos-marketing/src/main/resources/permissions.yaml`, `pos-marketing/src/main/resources/application.yml`,
  `pos-marketing/README.md`
- `pos-customer/src/main/resources/permissions.yaml` (segment, consent and suppression codes)
- `pos-mcp-server/src/main/java/com/positivity/mcp/internal/orchestration/TaggingQuestions.java` (the `risk` question)
- `durion/docs/adr/0044-platform-event-only-domain-walls.adr.md`,
  `durion/docs/adr/0068-mcp-pre-llm-question-tagging-decision-model.adr.md`
- `durion/domains/crm/.business-rules/AGENT_GUIDE.md`

External sources:

1. "Email marketing", Wikipedia, Wikimedia Foundation. <https://en.wikipedia.org/wiki/Email_marketing>
   (accessed 2026-10-02).
2. "Market segmentation", Wikipedia, Wikimedia Foundation: "the process of dividing a consumer or business market into
   meaningful sub-groups of current or potential customers". <https://en.wikipedia.org/wiki/Market_segmentation>
   (accessed 2026-10-02).
3. "Canada's Anti-Spam Legislation", Wikipedia, Wikimedia Foundation.
   <https://en.wikipedia.org/wiki/Canada%27s_Anti-Spam_Legislation> (accessed 2026-10-02).
4. "CAN-SPAM Act of 2003", Wikipedia, Wikimedia Foundation. <https://en.wikipedia.org/wiki/CAN-SPAM_Act_of_2003>
   (accessed 2026-10-02).
