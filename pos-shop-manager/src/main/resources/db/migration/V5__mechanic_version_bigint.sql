-- The mechanic sync feed (PeopleEventsListener -> MechanicSyncService) carries
-- people.events.v1 aggregateVersion values, which are emission timestamps in
-- epoch millis and overflow the original integer column.
ALTER TABLE mechanic ALTER COLUMN version TYPE bigint;
