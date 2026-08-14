-- Story #1148: carry the CRM's truncation flag onto the audience snapshot.
--
-- customer.segment.resolved already reports whether the owner's candidate scan hit its ceiling
-- and the membership list is partial, but the flag was dropped on arrival and audience preview
-- reported every snapshot as complete. A marketer reading a count that is quietly short has no
-- way to tell an audience that is genuinely small from one that was cut off, so the flag is
-- stored with the snapshot it describes and surfaced in the preview.
--
-- The value is a property of the snapshot rather than of the member, so every row written by
-- one resolution carries the same value; existing rows predate the flag and default to false,
-- which is what they were being reported as anyway.
ALTER TABLE campaign_audience_member
    ADD COLUMN truncated boolean NOT NULL DEFAULT FALSE;
