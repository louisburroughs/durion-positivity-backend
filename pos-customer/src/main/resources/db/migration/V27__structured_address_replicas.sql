-- FI-4 (#1135): structured international address from pos-people-contact.
--
-- Person addresses ride on people-contact.person.updated (nullable postalAddress added to the
-- v1 payload) and are flattened onto the existing person replica. Organization addresses arrive
-- as people-contact.organization-address.updated/.removed facts keyed by the commercial party
-- UUID this module minted, and land in their own replica table.
--
-- The legacy free-text primary_address columns on person_party/commercial_party stay: they are
-- unparseable into structured components with any reliability, so there is no automated
-- backfill — the structured address supersedes them as it gets captured, and geo/region segment
-- predicates read only the structured replicas.

ALTER TABLE ext_people_contact_person ADD COLUMN address_line1 character varying(255);
ALTER TABLE ext_people_contact_person ADD COLUMN address_line2 character varying(255);
ALTER TABLE ext_people_contact_person ADD COLUMN address_city character varying(120);
ALTER TABLE ext_people_contact_person ADD COLUMN address_region character varying(120);
ALTER TABLE ext_people_contact_person ADD COLUMN address_postal_code character varying(20);
ALTER TABLE ext_people_contact_person ADD COLUMN address_country_code character varying(2);

CREATE TABLE ext_organization_postal_address (
    organization_id uuid NOT NULL,
    line1 character varying(255),
    line2 character varying(255),
    city character varying(120),
    region character varying(120),
    postal_code character varying(20),
    country_code character varying(2),
    aggregate_version bigint NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ext_organization_postal_address_pkey PRIMARY KEY (organization_id)
);
