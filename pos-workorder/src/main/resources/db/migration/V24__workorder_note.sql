-- Issue #1584: notes about the customer, recorded while a workorder is worked.
--
-- pos-customer ran a listener for a PartyNoteAdded event that nothing published, on a topic that
-- nothing wrote to. The integration was intended, so this is the missing producer side: the
-- workorder owns the note, and workorder.note.added.v1 projects it onto the CRM timeline.
--
-- Distinct from the note columns already on workorder (completion_notes, approval_notes) and
-- change_request (approval_note): those describe the work or a decision about it, are
-- single-valued, and are not about the customer.
CREATE TABLE workorder_note (
    note_id uuid NOT NULL,
    workorder_id uuid NOT NULL,
    note_type character varying(100),
    note_text character varying(2000) NOT NULL,
    authored_by character varying(255),
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT pk_workorder_note PRIMARY KEY (note_id)
);

-- Reads are always "the notes on this workorder, newest first".
CREATE INDEX idx_workorder_note_workorder ON workorder_note (workorder_id, created_at);
