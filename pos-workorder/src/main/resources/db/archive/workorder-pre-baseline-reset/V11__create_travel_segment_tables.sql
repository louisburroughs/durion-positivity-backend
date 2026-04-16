-- Story #67 - Mobile Travel Segment Capture
CREATE TABLE travel_segment (
    travel_segment_id       UUID                     NOT NULL,
    mobile_work_assignment_id UUID                   NOT NULL,
    technician_id           UUID                     NOT NULL,
    segment_type            VARCHAR(50)              NOT NULL,
    start_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    end_at                  TIMESTAMP WITH TIME ZONE,
    from_location_id        UUID,
    to_location_id          UUID,
    work_order_id           UUID,
    duration_minutes        INT,
    raw_minutes             INT,
    buffered_minutes        INT,
    status                  VARCHAR(50)              NOT NULL DEFAULT 'DRAFT',
    created_by              VARCHAR(255)             NOT NULL,
    last_modified_by        VARCHAR(255),
    last_modified_at        TIMESTAMP WITH TIME ZONE,
    acted_by_user_id        VARCHAR(255),
    acted_for_person_id     UUID,
    on_behalf_reason_code   VARCHAR(50),
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_travel_segment PRIMARY KEY (travel_segment_id)
);

CREATE TABLE travel_segment_adjustment (
    adjustment_id           UUID                     NOT NULL,
    travel_segment_id       UUID                     NOT NULL,
    adjusted_start_at       TIMESTAMP WITH TIME ZONE,
    adjusted_end_at         TIMESTAMP WITH TIME ZONE,
    adjustment_reason       TEXT                     NOT NULL,
    adjusted_by_user_id     VARCHAR(255)             NOT NULL,
    approval_status         VARCHAR(50)              NOT NULL DEFAULT 'PENDING',
    approved_by_user_id     VARCHAR(255),
    approved_at             TIMESTAMP WITH TIME ZONE,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_travel_segment_adjustment PRIMARY KEY (adjustment_id),
    CONSTRAINT fk_tsa_travel_segment FOREIGN KEY (travel_segment_id) REFERENCES travel_segment(travel_segment_id) ON DELETE CASCADE
);

CREATE INDEX idx_travel_segment_mobile_work_assignment_id ON travel_segment(mobile_work_assignment_id);
CREATE INDEX idx_travel_segment_technician_id ON travel_segment(technician_id);
CREATE INDEX idx_travel_segment_status ON travel_segment(status);
CREATE INDEX idx_travel_segment_assignment_technician ON travel_segment(mobile_work_assignment_id, technician_id);
CREATE INDEX idx_tsa_travel_segment_id ON travel_segment_adjustment(travel_segment_id);
