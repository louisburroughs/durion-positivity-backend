-- PR #1089 ADR-0044 resolution (stories I2/H1): event-fed CRM replicas replace synchronous REST
-- toward pos-customer; pos-inventory availability reverts to advisory pending an event feed.
CREATE TABLE IF NOT EXISTS ext_customer (
  party_id UUID PRIMARY KEY,
  status VARCHAR(64) NOT NULL,
  display_name VARCHAR(255),
  aggregate_version BIGINT NOT NULL,
  synced_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS ext_vehicle (
  vehicle_id UUID PRIMARY KEY,
  account_id UUID NOT NULL,
  active BOOLEAN NOT NULL,
  aggregate_version BIGINT NOT NULL,
  synced_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ext_vehicle_account ON ext_vehicle (account_id);
