CREATE INDEX idx_inventory_ledger_entry_location_event ON inventory_ledger_entry USING btree (location_id, event_type);
