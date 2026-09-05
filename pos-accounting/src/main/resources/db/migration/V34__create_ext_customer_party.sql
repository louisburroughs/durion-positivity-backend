-- ADR-0044 §6 (issues #889 / #891, consumed here for #1779): read-only replica of pos-customer
-- party identity, populated exclusively by the customer.events.v1 consumer
-- (CustomerEventsListener). Never written by accounting business logic, and never read to derive
-- a posting amount or a ledger fact — only to render a display value.
--
-- Accounting responses carry party UUIDs (credit memos, AR records) that user-facing screens
-- cannot show as-is. pos-customer owns the name and the customer number; this replica is how
-- accounting learns them without a synchronous cross-domain call. Both display columns are
-- nullable at the source, and a party the replica does not know simply resolves to nothing —
-- a UUID is never substituted as display text.
--
-- Mirrors the ext_customer_party replicas already maintained by pos-invoice (V6), pos-workorder
-- and pos-shop-manager off the same customer.party.updated fact, with customer_number added:
-- those consumers search by name only, while accounting also shows the owner's stable
-- human-facing customer number.
CREATE TABLE ext_customer_party (
    party_id uuid NOT NULL,
    party_type character varying(32) NOT NULL,
    display_name character varying(255),
    customer_number character varying(64),
    status character varying(32) NOT NULL,
    aggregate_version bigint NOT NULL DEFAULT 0,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_customer_party_pkey PRIMARY KEY (party_id)
);
