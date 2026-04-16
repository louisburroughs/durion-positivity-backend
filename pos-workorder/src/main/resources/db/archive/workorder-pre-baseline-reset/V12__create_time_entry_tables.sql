-- Story #66 - Approve Submitted Time
CREATE TABLE time_entry (
    time_entry_id       UUID                     NOT NULL,
    person_id           UUID                     NOT NULL,
    work_order_id       UUID                     NOT NULL,
    start_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    end_at              TIMESTAMP WITH TIME ZONE,
    status              VARCHAR(50)              NOT NULL DEFAULT 'DRAFT',
    submitted_at        TIMESTAMP WITH TIME ZONE,
    decision_by_user_id VARCHAR(255),
    decision_at_utc     TIMESTAMP WITH TIME ZONE,
    rejection_reason    TEXT,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_time_entry PRIMARY KEY (time_entry_id)
);

CREATE TABLE time_entry_adjustment (
    adjustment_id       UUID                     NOT NULL,
    time_entry_id       UUID                     NOT NULL,
    requested_by        VARCHAR(255)             NOT NULL,
    reason_code         VARCHAR(50)              NOT NULL,
    notes               TEXT,
    proposed_start_at   TIMESTAMP WITH TIME ZONE,
    proposed_end_at     TIMESTAMP WITH TIME ZONE,
    minutes_delta       INT,
    status              VARCHAR(50)              NOT NULL DEFAULT 'PROPOSED',
    approved_by         VARCHAR(255),
    approved_at         TIMESTAMP WITH TIME ZONE,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_time_entry_adjustment PRIMARY KEY (adjustment_id),
    CONSTRAINT fk_tea_time_entry FOREIGN KEY (time_entry_id) REFERENCES time_entry(time_entry_id) ON DELETE CASCADE
);

CREATE INDEX idx_time_entry_person_id ON time_entry(person_id);
CREATE INDEX idx_time_entry_work_order_id ON time_entry(work_order_id);
CREATE INDEX idx_time_entry_status ON time_entry(status);
CREATE INDEX idx_tea_time_entry_id ON time_entry_adjustment(time_entry_id);
