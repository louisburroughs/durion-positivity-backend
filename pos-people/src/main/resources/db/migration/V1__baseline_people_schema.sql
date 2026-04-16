-- Collapsed baseline for pos-accounting generated from emitted Postgres schema.

-- Source dump: /tmp/pos_people_baseline_emitted.sql

-- Repeatable seed/data migrations remain separate and are not folded into this file.



SET TIME ZONE 'UTC';



CREATE TABLE employee_offboarding_retry_queue ( attempts integer NOT NULL, created_at timestamp(6) with time zone NOT NULL, next_attempt_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone, employee_id uuid NOT NULL, id uuid NOT NULL, actor_id character varying(255) NOT NULL, assignment_policy character varying(255) NOT NULL, disable_reason character varying(255), failure_reason character varying(255) NOT NULL, CONSTRAINT employee_offboarding_retry_queue_assignment_policy_check CHECK (((assignment_policy)::text = ANY ((ARRAY['IMMEDIATE'::character varying, 'GRACE_PERIOD'::character varying])::text[]))) );

CREATE TABLE person ( hire_date date, termination_date date, created_at timestamp(6) with time zone, status_effective_at timestamp(6) with time zone, updated_at timestamp(6) with time zone, id uuid NOT NULL, employee_number character varying(255), first_name character varying(255), last_name character varying(255), legal_name character varying(255), preferred_name character varying(255), primary_email character varying(255), secondary_email character varying(255), status character varying(255), username character varying(255), contact_info_json oid, CONSTRAINT person_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'ON_LEAVE'::character varying, 'SUSPENDED'::character varying, 'TERMINATED'::character varying, 'DISABLED'::character varying])::text[]))) );

CREATE TABLE person_location_assignment ( effective_from date NOT NULL, effective_to date, is_primary boolean NOT NULL, created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone, id uuid NOT NULL, location_id uuid NOT NULL, person_id uuid NOT NULL, created_by character varying(255), role character varying(255) NOT NULL, status character varying(255) NOT NULL, CONSTRAINT person_location_assignment_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'ENDED'::character varying])::text[]))) );

CREATE TABLE person_phone_numbers ( person_id uuid NOT NULL, phone_numbers character varying(255) );

CREATE TABLE time_entry ( approved_at timestamp(6) with time zone, attendance_end_at timestamp(6) with time zone, attendance_start_at timestamp(6) with time zone, created_at timestamp(6) with time zone, rejected_at timestamp(6) with time zone, updated_at timestamp(6) with time zone, location_id uuid, person_id uuid, time_entry_id uuid NOT NULL, rejection_reason character varying(500), approved_by character varying(255), rejected_by character varying(255), status character varying(255), timesheet_id character varying(255), CONSTRAINT time_entry_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'SUBMITTED'::character varying, 'PENDING_APPROVAL'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying])::text[]))) );

CREATE TABLE time_entry_adjustment ( minutes_delta integer, created_at timestamp(6) with time zone, decided_at timestamp(6) with time zone, proposed_end_at timestamp(6) with time zone, proposed_start_at timestamp(6) with time zone, updated_at timestamp(6) with time zone, adjustment_id uuid NOT NULL, time_entry_id uuid NOT NULL, status character varying(50), reason_code character varying(200), created_by character varying(255), decided_by character varying(255), notes text, CONSTRAINT time_entry_adjustment_status_check CHECK (((status)::text = ANY ((ARRAY['PROPOSED'::character varying, 'PENDING'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying])::text[]))) );

CREATE TABLE time_entry_audit ( created_at timestamp(6) with time zone, "timestamp" timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone, audit_id uuid NOT NULL, action character varying(100) NOT NULL, correlation_id character varying(100), actor_id character varying(255), details text, time_entry_id character varying(255) NOT NULL );

CREATE TABLE time_entry_exception ( work_date date, created_at timestamp(6) with time zone, detected_at timestamp(6) with time zone, resolved_at timestamp(6) with time zone, updated_at timestamp(6) with time zone, exception_id uuid NOT NULL, severity character varying(50), status character varying(50), exception_code character varying(200), employee_id character varying(255) NOT NULL, resolution_notes text, resolved_by character varying(255), time_entry_id character varying(255), CONSTRAINT time_entry_exception_severity_check CHECK (((severity)::text = ANY ((ARRAY['WARNING'::character varying, 'BLOCKING'::character varying])::text[]))), CONSTRAINT time_entry_exception_status_check CHECK (((status)::text = ANY ((ARRAY['OPEN'::character varying, 'ACKNOWLEDGED'::character varying, 'RESOLVED'::character varying, 'WAIVED'::character varying])::text[]))) );

CREATE TABLE timekeeping_entry ( created_at timestamp(6) with time zone NOT NULL, session_end_time timestamp(6) with time zone NOT NULL, session_start_time timestamp(6) with time zone NOT NULL, associated_work_order_id uuid, correction_id uuid, employee_id uuid NOT NULL, original_source_session_id uuid, source_session_id uuid NOT NULL, tenant_id uuid NOT NULL, timekeeping_entry_id uuid NOT NULL, approval_status character varying(50) NOT NULL, source_system character varying(50) NOT NULL, correction_reason text, CONSTRAINT timekeeping_entry_approval_status_check CHECK (((approval_status)::text = ANY ((ARRAY['PENDING_APPROVAL'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying])::text[]))) );

CREATE TABLE timekeeping_policy ( job_time_discrepancy_threshold_minutes integer NOT NULL, created_at timestamp(6) with time zone, effective_end_at timestamp(6) with time zone, effective_start_at timestamp(6) with time zone, updated_at timestamp(6) with time zone NOT NULL, scope_id uuid, scope_type character varying(16) NOT NULL, timekeeping_policy_id uuid NOT NULL, updated_by character varying(255), CONSTRAINT timekeeping_policy_scope_type_check CHECK (((scope_type)::text = ANY ((ARRAY['GLOBAL'::character varying, 'LOCATION'::character varying])::text[]))) );

CREATE TABLE user_person_links ( created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone, id uuid NOT NULL, person_id uuid NOT NULL, user_id uuid NOT NULL, status character varying(20) NOT NULL, link_type character varying(50), notes character varying(1000), created_by character varying(255), CONSTRAINT user_person_links_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying])::text[]))) );

CREATE TABLE work_session ( created_at timestamp(6) with time zone, ended_at timestamp(6) with time zone, started_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone, person_id uuid NOT NULL, session_id uuid NOT NULL, status character varying(32) NOT NULL, actor character varying(128) );

CREATE TABLE work_session_break ( created_at timestamp(6) with time zone, ended_at timestamp(6) with time zone, started_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone, break_id uuid NOT NULL, session_id uuid NOT NULL, actor character varying(128) );

ALTER TABLE employee_offboarding_retry_queue ADD CONSTRAINT employee_offboarding_retry_queue_pkey PRIMARY KEY (id);

ALTER TABLE person_location_assignment ADD CONSTRAINT person_location_assignment_person_id_location_id_role_effec_key UNIQUE (person_id, location_id, role, effective_from);

ALTER TABLE person_location_assignment ADD CONSTRAINT person_location_assignment_pkey PRIMARY KEY (id);

ALTER TABLE person ADD CONSTRAINT person_pkey PRIMARY KEY (id);

ALTER TABLE time_entry_adjustment ADD CONSTRAINT time_entry_adjustment_pkey PRIMARY KEY (adjustment_id);

ALTER TABLE time_entry_audit ADD CONSTRAINT time_entry_audit_pkey PRIMARY KEY (audit_id);

ALTER TABLE time_entry_exception ADD CONSTRAINT time_entry_exception_pkey PRIMARY KEY (exception_id);

ALTER TABLE time_entry ADD CONSTRAINT time_entry_pkey PRIMARY KEY (time_entry_id);

ALTER TABLE timekeeping_entry ADD CONSTRAINT timekeeping_entry_pkey PRIMARY KEY (timekeeping_entry_id);

ALTER TABLE timekeeping_entry ADD CONSTRAINT timekeeping_entry_tenant_id_source_system_source_session_id_key UNIQUE (tenant_id, source_system, source_session_id);

ALTER TABLE timekeeping_policy ADD CONSTRAINT timekeeping_policy_pkey PRIMARY KEY (timekeeping_policy_id);

ALTER TABLE user_person_links ADD CONSTRAINT user_person_links_pkey PRIMARY KEY (id);

ALTER TABLE user_person_links ADD CONSTRAINT user_person_links_user_id_key UNIQUE (user_id);

ALTER TABLE work_session_break ADD CONSTRAINT work_session_break_pkey PRIMARY KEY (break_id);

ALTER TABLE work_session ADD CONSTRAINT work_session_pkey PRIMARY KEY (session_id);

CREATE INDEX idx_time_entry_attendance_window ON time_entry USING btree (attendance_start_at, attendance_end_at);

CREATE INDEX idx_time_entry_location_start ON time_entry USING btree (location_id, attendance_start_at);

CREATE INDEX idx_time_entry_person_start ON time_entry USING btree (person_id, attendance_start_at);

CREATE INDEX idx_timekeeping_policy_scope ON timekeeping_policy USING btree (scope_type, scope_id);

CREATE INDEX idx_timekeeping_policy_scope_effective_updated ON timekeeping_policy USING btree (scope_type, scope_id, effective_start_at, effective_end_at, updated_at);

CREATE INDEX idx_user_person_links_person_id ON user_person_links USING btree (person_id);

ALTER TABLE work_session_break ADD CONSTRAINT fk4frye7mf37xweamedysh2lgkt FOREIGN KEY (session_id) REFERENCES work_session(session_id);

ALTER TABLE work_session ADD CONSTRAINT fkagbjpp19kgt2auo2wxofmmdbk FOREIGN KEY (person_id) REFERENCES person(id);

ALTER TABLE employee_offboarding_retry_queue ADD CONSTRAINT fkc0d3sdmlk84d64hdrbch44la6 FOREIGN KEY (employee_id) REFERENCES person(id);

ALTER TABLE person_phone_numbers ADD CONSTRAINT fkiaw204pko4dbjbh7bt2p4qbbo FOREIGN KEY (person_id) REFERENCES person(id);

ALTER TABLE time_entry_adjustment ADD CONSTRAINT fkls70vixxyk0xncsnq2x2jphjq FOREIGN KEY (time_entry_id) REFERENCES time_entry(time_entry_id);

ALTER TABLE user_person_links ADD CONSTRAINT fkmgja1yh7vx99k7rh0fh1tvhb5 FOREIGN KEY (person_id) REFERENCES person(id);

ALTER TABLE person_location_assignment ADD CONSTRAINT fkqhx0ssgkqmikua0r6ruxwpbe3 FOREIGN KEY (person_id) REFERENCES person(id);
