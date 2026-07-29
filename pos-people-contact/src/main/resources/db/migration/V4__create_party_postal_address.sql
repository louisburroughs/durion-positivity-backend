-- FI-4 (#1135): structured, international postal address owned by pos-people-contact.
-- One primary postal address per party. PERSON rows reference person.id (validated in the
-- service layer); ORGANIZATION rows carry the organization/commercial-party UUID owned by
-- another module (pos-customer), so there is deliberately no FK (no cross-service foreign keys).
--
-- The shape is country-agnostic so the platform can deploy anywhere: region is a free-text
-- administrative area (state, province, prefecture, county), postal_code is nullable because
-- several countries do not use one, and no country-specific format is enforced beyond
-- country_code being ISO 3166-1 alpha-2. Existing free-text addresses (pos-customer
-- primary_address) cannot be parsed into components reliably, so there is no automated
-- backfill; they remain readable until re-entered structured.

create table party_postal_address (
    id UUID not null,
    party_type varchar(20) not null check ((party_type in ('PERSON','ORGANIZATION'))),
    party_id uuid not null,
    line1 varchar(255) not null,
    line2 varchar(255),
    city varchar(120),
    region varchar(120),
    postal_code varchar(20),
    country_code varchar(2) not null,
    created_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    primary key (id),
    constraint uq_party_postal_address_party unique (party_type, party_id)
);

create index idx_party_postal_address_party on party_postal_address (party_id);
