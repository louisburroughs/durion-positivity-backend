-- CAP-320 #1330: transmitting an approved purchase order to its vendor, and hearing back.
--
-- pos-supplier can already mint an intent, send a C1.0 order, poll C1.1 status and escalate a
-- stuck send. Nothing asked it to and nothing heard the answers. These columns are the order's
-- half of that conversation.

-- The vendor as pos-supplier knows it: its profile alias, chosen when the order is raised.
--
-- vendor_id cannot serve. It holds a manufacturer or distributor id from pos-inventory's
-- normalized feeds, which has no relationship to a supplier profile — so who to send to is
-- recorded as an ordering decision rather than inferred later from something that does not mean
-- what it would need to mean. An order with no supplier_ref is simply not transmittable.
ALTER TABLE purchase_order ADD COLUMN supplier_ref varchar(100);

-- Where the transmission has got to, from this domain's point of view.
--
-- NOT_TRANSMITTED, REQUESTED, CONFIRMED, REJECTED, MANUAL_REVIEW. Deliberately this domain's own
-- state and not a mirror of pos-supplier's intent ledger: the buyer looks at the order, and a
-- state that only exists in the transmission log is a state the buyer cannot see.
ALTER TABLE purchase_order ADD COLUMN transmission_state varchar(32) NOT NULL DEFAULT 'NOT_TRANSMITTED';

-- The intent pos-supplier minted for the current transmission, echoed back on every result event.
ALTER TABLE purchase_order ADD COLUMN transmission_intent_id uuid;

-- The vendor document id pos-supplier derived, and the vendor's own order number once it gives one.
ALTER TABLE purchase_order ADD COLUMN vendor_document_id varchar(255);
ALTER TABLE purchase_order ADD COLUMN supplier_order_number varchar(255);

-- Why the vendor refused, when it did.
ALTER TABLE purchase_order ADD COLUMN transmission_rejection_reason varchar(64);
ALTER TABLE purchase_order ADD COLUMN transmission_vendor_reason varchar(1024);

-- When the transmission was requested, and when it last changed. observed_at is the vendor's
-- clock as reported; a result older than what is already applied is ignored, which is what makes
-- an out-of-order arrival harmless rather than a regression.
ALTER TABLE purchase_order ADD COLUMN transmission_requested_at timestamp(6) with time zone;
ALTER TABLE purchase_order ADD COLUMN transmission_observed_at timestamp(6) with time zone;

-- How many times this order has been transmitted. Distinguishes a first send from a re-order,
-- which is what decides whether the next request is INITIAL or REPLACEMENT — pos-supplier cannot
-- tell them apart from the payload, and getting it wrong is a second lorry rather than a 400.
ALTER TABLE purchase_order ADD COLUMN transmission_count integer NOT NULL DEFAULT 0;

-- The version of the order as last transmitted. A later revision of an order the vendor already
-- holds is a REVISION; without this there is nothing to compare against and every re-send would
-- look like a first one.
ALTER TABLE purchase_order ADD COLUMN transmitted_version_number integer;

CREATE INDEX idx_purchase_order_transmission_intent ON purchase_order (transmission_intent_id);

-- What the vendor said, in the order it was heard.
--
-- A timeline rather than a current-value column, because "confirmed, then despatched, then
-- delivered late" is the thing a buyer chasing an order actually needs, and each step carries
-- dates the previous one did not.
CREATE TABLE purchase_order_transmission_event (
    event_id                uuid PRIMARY KEY,
    purchase_order_id       uuid NOT NULL REFERENCES purchase_order (purchase_order_id),
    transmission_intent_id  uuid,
    event_type              varchar(64) NOT NULL,
    status                  varchar(32),
    vendor_document_id      varchar(255),
    supplier_order_number   varchar(255),
    vendor_reason           varchar(1024),
    despatch_date           date,
    estimated_delivery_date date,
    observed_at             timestamp(6) with time zone NOT NULL,
    recorded_at             timestamp(6) with time zone NOT NULL
);

CREATE INDEX idx_po_transmission_event_order ON purchase_order_transmission_event (purchase_order_id, observed_at);
