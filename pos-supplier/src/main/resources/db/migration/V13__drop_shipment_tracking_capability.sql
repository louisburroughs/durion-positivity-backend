-- #1313: SHIPMENT_TRACKING is removed from the bindable capability set.
--
-- RENUMBERED V12 -> V13 (#1319). This landed as V12 in #1317 while #1316 was landing its own V12
-- (V12__pricat_reapplication.sql); neither PR could see the other's version number. Flyway refuses
-- to initialize with two migrations at one version, so pos-supplier could not start and every
-- module's build failed the migration-hygiene check until one of them moved. This file moved rather
-- than the other because it merged second: an environment that deployed in the window between the
-- two merges would already have recorded V12__pricat_reapplication, and renumbering that one would
-- have invalidated its checksum. Nothing can have applied THIS file in the meantime, because the
-- duplicate made the service unbootable from the moment it landed.
--
-- WHY THE CAPABILITY GOES RATHER THAN WAITS
--
-- CAP-322 (#1228) carried a shipment half alongside the stock report, on the reading that the vendor
-- exposes tracking we poll. The spec it named, ShipmentTrackingOAS_v1.yaml, declares exactly one
-- operation -- POST /shipment-tracking, a write a caller uses to ANNOUNCE a shipment notice. There is
-- no GET and no query by order reference, so the fetch-by-order-reference port had no operation to
-- call and no adapter was ever written for it.
--
-- The clarification (#1313) settles why: EDIWheel shipment tracking is an exchange between logistics
-- providers and suppliers. A service provider is not a party to it in either direction -- we are not
-- the carrier announcing movements, and the norm gives the ordering side nothing to read back. The
-- capability was never implementable here, so leaving the key bindable would only let an operator
-- configure an endpoint that resolves to CAPABILITY_NOT_CONFIGURED forever.
--
-- WHY EXISTING ROWS ARE DELETED RATHER THAN MIGRATED
--
-- A SHIPMENT_TRACKING binding is inert configuration by construction: no codec was ever registered
-- for the key, so no exchange has ever run through one and no data hangs off it. There is nothing to
-- carry forward, and the rewritten CHECK is validated against existing rows, so a surviving row would
-- fail the migration instead. Exchange-audit rows are untouched -- they hold no foreign key to the
-- binding (ADR-0050 §7) and, being empty of shipment traffic for the reason above, have nothing to
-- lose either way.
--
-- Should a non-EDIWheel source ever offer shipment milestones -- a carrier API, a Michelin S2S
-- operation -- that arrives as its own capability with its own spec and its own migration, not as a
-- revival of this key.
DELETE FROM supplier_endpoint_binding WHERE capability = 'SHIPMENT_TRACKING';

ALTER TABLE supplier_endpoint_binding
    DROP CONSTRAINT chk_sbinding_capability;

ALTER TABLE supplier_endpoint_binding
    ADD CONSTRAINT chk_sbinding_capability CHECK (capability IN (
        'ORDER_CREATE', 'ORDER_STATUS', 'STOCK_INQUIRY', 'STOCK_REPORT', 'PRICE_CATALOG',
        'INVOICE_FETCH', 'WORKORDER_AUTHORIZATION', 'MARKETING_CATALOG', 'TIRE_IDENTIFICATION'));
