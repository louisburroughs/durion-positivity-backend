-- Story #1150: engagement timestamps from the shared platform sender.
-- Opens and clicks are best-effort — SMS and some email providers never report them — so both
-- columns are nullable and the funnel (Story #1152) treats null as "not tracked", never as
-- "not opened". Only the first open/click is kept; per-event analytics stay with the sender.
ALTER TABLE campaign_send
    ADD COLUMN opened_at timestamp(6) with time zone,
    ADD COLUMN clicked_at timestamp(6) with time zone;
