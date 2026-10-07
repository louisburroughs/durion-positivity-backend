-- Register float relocation (#2571; SPEC-accounting-workspace §4.6 and §7.1 "Float", AW32).
--
-- A register's float moves to another location with one command, for a register set up under the wrong
-- location (ENTERED_IN_ERROR) or a drawer that physically moved (MOVED). Both post the same reclass:
-- Dr 1080 {register, destination} / Cr 1080 {register, origin} for the float, which is unchanged; a zero
-- float posts nothing. The reversal of a go-live or Change float entry made before a move writes a
-- RELOCATION row too (REVERSAL_FOLLOW_UP), for the reclass that brings the reversed amount to the
-- register's current location.
--
-- register_float_change gains the RELOCATION kind, the origin location and the reason, and its entry
-- becomes optional for that kind only.

ALTER TABLE public.register_float_change DROP CONSTRAINT register_float_change_kind_check;
ALTER TABLE public.register_float_change
    ADD CONSTRAINT register_float_change_kind_check
        CHECK ((kind)::text = ANY (ARRAY['GO_LIVE'::text, 'CHANGE'::text, 'REVERSAL'::text, 'RELOCATION'::text]));

ALTER TABLE public.register_float_change ADD COLUMN previous_location_id uuid;
ALTER TABLE public.register_float_change ADD COLUMN reason character varying(20);
ALTER TABLE public.register_float_change
    ADD CONSTRAINT register_float_change_reason_check
        CHECK (reason IS NULL
            OR (reason)::text = ANY (ARRAY['ENTERED_IN_ERROR'::text, 'MOVED'::text, 'REVERSAL_FOLLOW_UP'::text]));
-- A RELOCATION row names where the register came from and why; no other kind does.
ALTER TABLE public.register_float_change
    ADD CONSTRAINT register_float_change_relocation_check
        CHECK (((kind)::text = 'RELOCATION'::text) = (previous_location_id IS NOT NULL AND reason IS NOT NULL));

-- Only a relocation of a zero float posts nothing.
ALTER TABLE public.register_float_change ALTER COLUMN journal_entry_id DROP NOT NULL;
ALTER TABLE public.register_float_change
    ADD CONSTRAINT register_float_change_entry_check
        CHECK (journal_entry_id IS NOT NULL OR (kind)::text = 'RELOCATION'::text);

COMMENT ON COLUMN public.register_float_change.location_id IS
    'The register''s location after this change; for a RELOCATION, the destination.';
COMMENT ON COLUMN public.register_float_change.previous_location_id IS
    'RELOCATION only: the location the register was held at before the move (#2571, AW32).';
COMMENT ON COLUMN public.register_float_change.reason IS
    'RELOCATION only: ENTERED_IN_ERROR, MOVED, or REVERSAL_FOLLOW_UP (written by a reversal, never by a caller).';
COMMENT ON TABLE public.register_float IS
    'The change float of one register (#2511; AW16): a fixed amount kept in its drawer, held on 1080 Register '
    'Float. Set and changed only by the go-live and Change float commands (or their reversal); its location '
    'changes only by a relocation (#2571; AW32).';
