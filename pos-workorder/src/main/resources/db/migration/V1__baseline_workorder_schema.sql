-- Collapsed baseline for pos-accounting generated from emitted Postgres schema.

-- Source dump: /tmp/pos_workorder_baseline_emitted.sql

-- Repeatable seed/data migrations remain separate and are not folded into this file.



SET TIME ZONE 'UTC';



CREATE TABLE approval_configuration ( approval_window_days integer, decline_expiry_days integer, priority integer, require_signature boolean, created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, customer_id uuid, id uuid NOT NULL, location_id uuid, approval_method character varying(255), CONSTRAINT approval_configuration_approval_method_check CHECK (((approval_method)::text = ANY ((ARRAY['CLICK_CONFIRM'::character varying, 'SIGNATURE'::character varying, 'ELECTRONIC_SIGNATURE'::character varying, 'VERBAL_CONFIRMATION'::character varying])::text[]))) );

CREATE TABLE approval_record ( created_at timestamp(6) with time zone NOT NULL, resolved_at timestamp(6) without time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, change_request_id uuid NOT NULL, id uuid NOT NULL, workorder_id uuid NOT NULL, approval_note text, exception_reason text, resolution_status character varying(255) NOT NULL, resolved_by character varying(255) NOT NULL, CONSTRAINT approval_record_resolution_status_check CHECK (((resolution_status)::text = ANY ((ARRAY['APPROVED'::character varying, 'REJECTED'::character varying, 'APPROVED_WITH_EXCEPTION'::character varying])::text[]))) );

CREATE TABLE audit_events ( created_at timestamp(6) with time zone NOT NULL, event_timestamp timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, entity_id uuid NOT NULL, id uuid NOT NULL, details text, entity_type character varying(255) NOT NULL, event_type character varying(255) NOT NULL, user_id character varying(255) NOT NULL );

CREATE TABLE break_segment ( break_end_at timestamp(6) with time zone, break_start_at timestamp(6) with time zone NOT NULL, created_at timestamp(6) with time zone NOT NULL, break_segment_id uuid NOT NULL, work_session_id uuid NOT NULL, break_type character varying(255), notes text, CONSTRAINT break_segment_break_type_check CHECK (((break_type)::text = ANY ((ARRAY['MEAL'::character varying, 'REST'::character varying, 'OTHER'::character varying])::text[]))) );

CREATE TABLE change_request ( is_approval_gated boolean NOT NULL, is_emergency_exception boolean NOT NULL, approved_at timestamp(6) without time zone, created_at timestamp(6) with time zone NOT NULL, declined_at timestamp(6) without time zone, requested_at timestamp(6) without time zone NOT NULL, supplemental_estimate_pdf_id bigint, updated_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, workorder_id uuid NOT NULL, approval_note text, approved_by character varying(255), description text NOT NULL, exception_reason text, requested_by_user_id character varying(255) NOT NULL, status character varying(255) NOT NULL, CONSTRAINT change_request_status_check CHECK (((status)::text = ANY ((ARRAY['AWAITING_ADVISOR_REVIEW'::character varying, 'APPROVED'::character varying, 'DECLINED'::character varying, 'CANCELLED'::character varying, 'APPROVED_WITH_EXCEPTION'::character varying])::text[]))) );

CREATE TABLE customer ( created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, email character varying(255), name character varying(255), phone character varying(255) );

CREATE TABLE estimate ( subtotal numeric(38,2), tax_amount numeric(38,2), total numeric(38,2), version integer, approved_at timestamp(6) without time zone, created_at timestamp(6) with time zone NOT NULL, declined_at timestamp(6) without time zone, expires_at timestamp(6) without time zone, submitted_at timestamp(6) without time zone, updated_at timestamp(6) with time zone NOT NULL, appointment_id uuid, approval_configuration_id uuid, approved_by uuid, customer_id uuid, id uuid NOT NULL, location_id uuid, tax_region_id uuid, vehicle_id uuid, crm_party_id character varying(36), crm_vehicle_id character varying(36), purchase_order_number character varying(100), signature_data character varying(100000), approval_notes character varying(255), created_by_id character varying(255) NOT NULL, created_by_user_id character varying(255), crm_contact_ids text, currency_uom_id character varying(255), decline_reason character varying(255), estimate_number character varying(255), signature_mime_type character varying(255), signer_name character varying(255), status character varying(255), submitted_by character varying(255), CONSTRAINT estimate_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'PENDING_APPROVAL'::character varying, 'OPEN'::character varying, 'PENDING_CUSTOMER'::character varying, 'APPROVED'::character varying, 'DECLINED'::character varying, 'EXPIRED'::character varying, 'SCHEDULED'::character varying, 'INVOICED'::character varying, 'CANCELLED'::character varying, 'ARCHIVED'::character varying])::text[]))) );

CREATE TABLE estimate_item ( deleted boolean NOT NULL, line_total numeric(19,2), quantity numeric(19,4) NOT NULL, unit_price numeric(19,2) NOT NULL, approval_timestamp timestamp(6) without time zone, created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, approval_proof_id uuid, estimate_id uuid NOT NULL, id uuid NOT NULL, product_id uuid, service_id uuid, approval_status character varying(20), item_type character varying(20) NOT NULL, tax_code character varying(50), approval_method_used character varying(100), description character varying(500), approval_notes character varying(1000), rejection_reason character varying(1000), created_by_id character varying(255) NOT NULL, CONSTRAINT estimate_item_approval_status_check CHECK (((approval_status)::text = ANY ((ARRAY['PENDING_APPROVAL'::character varying, 'APPROVED'::character varying, 'DECLINED'::character varying])::text[]))), CONSTRAINT estimate_item_item_type_check CHECK (((item_type)::text = ANY ((ARRAY['PART'::character varying, 'LABOR'::character varying])::text[]))) );

CREATE TABLE estimate_sequence ( created_at timestamp(6) with time zone NOT NULL, last_sequence_number bigint NOT NULL, updated_at timestamp(6) with time zone NOT NULL, version bigint, id uuid NOT NULL );

CREATE TABLE estimate_snapshot ( captured_at timestamp(6) without time zone NOT NULL, created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, estimate_id uuid NOT NULL, id uuid NOT NULL, status character varying(20) NOT NULL, notes character varying(500), captured_by_id character varying(255) NOT NULL, snapshot_data text NOT NULL, CONSTRAINT estimate_snapshot_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'PENDING_APPROVAL'::character varying, 'OPEN'::character varying, 'PENDING_CUSTOMER'::character varying, 'APPROVED'::character varying, 'DECLINED'::character varying, 'EXPIRED'::character varying, 'SCHEDULED'::character varying, 'INVOICED'::character varying, 'CANCELLED'::character varying, 'ARCHIVED'::character varying])::text[]))) );

CREATE TABLE idempotency_keys ( created_at timestamp(6) with time zone NOT NULL, expires_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, change_request_id uuid, id uuid NOT NULL, invoice_id uuid, labor_entry_id uuid, part_adjustment_event_id uuid, part_usage_event_id uuid, workorder_id uuid, key_value character varying(255) NOT NULL );

CREATE TABLE substitute_audit ( "timestamp" timestamp(6) with time zone NOT NULL, audit_id uuid NOT NULL, link_id uuid NOT NULL, actor_id character varying(255) NOT NULL, correlation_id character varying(255), operation character varying(255) NOT NULL, payload_after text, payload_before text );

CREATE TABLE substitute_link ( is_active boolean NOT NULL, is_auto_suggest boolean NOT NULL, priority integer NOT NULL, version integer NOT NULL, created_at timestamp(6) with time zone NOT NULL, effective_from timestamp(6) with time zone, effective_to timestamp(6) with time zone, updated_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, product_id uuid NOT NULL, substitute_part_id uuid NOT NULL, created_by character varying(255) NOT NULL, substitute_type character varying(255) NOT NULL, updated_by character varying(255), CONSTRAINT substitute_link_substitute_type_check CHECK (((substitute_type)::text = ANY ((ARRAY['EQUIVALENT'::character varying, 'APPROVED_ALTERNATIVE'::character varying, 'UPGRADE'::character varying, 'DOWNGRADE'::character varying])::text[]))) );

CREATE TABLE technician_assignment ( current boolean NOT NULL, assigned_at timestamp(6) without time zone NOT NULL, created_at timestamp(6) with time zone NOT NULL, id bigint NOT NULL, unassigned_at timestamp(6) without time zone, updated_at timestamp(6) with time zone NOT NULL, technician_id uuid NOT NULL, workorder_id uuid NOT NULL, assigned_by text NOT NULL, notes text, reassignment_reason text );

ALTER TABLE technician_assignment ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY ( SEQUENCE NAME technician_assignment_id_seq START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1 );

CREATE TABLE time_entry ( created_at timestamp(6) with time zone NOT NULL, decision_at_utc timestamp(6) with time zone, end_at timestamp(6) with time zone, start_at timestamp(6) with time zone NOT NULL, submitted_at timestamp(6) with time zone, updated_at timestamp(6) with time zone NOT NULL, person_id uuid NOT NULL, time_entry_id uuid NOT NULL, work_order_id uuid NOT NULL, decision_by_user_id character varying(255), rejection_reason text, status character varying(255) NOT NULL, CONSTRAINT time_entry_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'SUBMITTED'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying])::text[]))) );

CREATE TABLE time_entry_adjustment ( minutes_delta integer, approved_at timestamp(6) with time zone, created_at timestamp(6) with time zone NOT NULL, proposed_end_at timestamp(6) with time zone, proposed_start_at timestamp(6) with time zone, adjustment_id uuid NOT NULL, time_entry_id uuid NOT NULL, approved_by character varying(255), notes text, reason_code character varying(255) NOT NULL, requested_by character varying(255) NOT NULL, status character varying(255) NOT NULL, CONSTRAINT time_entry_adjustment_reason_code_check CHECK (((reason_code)::text = ANY ((ARRAY['CLOCK_IN_MISSED'::character varying, 'CLOCK_OUT_MISSED'::character varying, 'INCORRECT_WORKORDER'::character varying, 'OVERTIME_APPROVED'::character varying, 'SYSTEM_CORRECTION'::character varying])::text[]))), CONSTRAINT time_entry_adjustment_status_check CHECK (((status)::text = ANY ((ARRAY['PROPOSED'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying])::text[]))) );

CREATE TABLE travel_segment ( buffered_minutes integer, duration_minutes integer, raw_minutes integer, created_at timestamp(6) with time zone NOT NULL, end_at timestamp(6) with time zone, last_modified_at timestamp(6) with time zone, start_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, acted_for_person_id uuid, from_location_id uuid, mobile_work_assignment_id uuid NOT NULL, technician_id uuid NOT NULL, to_location_id uuid, travel_segment_id uuid NOT NULL, work_order_id uuid, acted_by_user_id character varying(255), created_by character varying(255) NOT NULL, last_modified_by character varying(255), on_behalf_reason_code character varying(255), segment_type character varying(255) NOT NULL, status character varying(255) NOT NULL, CONSTRAINT travel_segment_on_behalf_reason_code_check CHECK (((on_behalf_reason_code)::text = ANY ((ARRAY['TECHNICIAN_UNAVAILABLE'::character varying, 'FORGOT_TO_CLOCK'::character varying, 'DATA_ENTRY_ERROR'::character varying])::text[]))), CONSTRAINT travel_segment_segment_type_check CHECK (((segment_type)::text = ANY ((ARRAY['DEPART_SHOP'::character varying, 'ARRIVE_CUSTOMER_SITE'::character varying, 'DEPART_CUSTOMER_SITE'::character varying, 'ARRIVE_SHOP'::character varying, 'TRAVEL_BETWEEN_SITES'::character varying, 'DEADHEAD'::character varying])::text[]))), CONSTRAINT travel_segment_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'SUBMITTED'::character varying, 'APPROVED'::character varying, 'CANCELLED'::character varying])::text[]))) );

CREATE TABLE travel_segment_adjustment ( adjusted_end_at timestamp(6) with time zone, adjusted_start_at timestamp(6) with time zone, approved_at timestamp(6) with time zone, created_at timestamp(6) with time zone NOT NULL, adjustment_id uuid NOT NULL, travel_segment_id uuid NOT NULL, adjusted_by_user_id character varying(255) NOT NULL, adjustment_reason text NOT NULL, approval_status character varying(255) NOT NULL, approved_by_user_id character varying(255) );

CREATE TABLE vehicle ( created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, color character varying(255), license_plate character varying(255), make character varying(255), model character varying(255), vin character varying(255), year character varying(255) );

CREATE TABLE work_order_snapshots ( captured_at timestamp(6) with time zone NOT NULL, created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, workorder_id uuid NOT NULL, captured_by character varying(255) NOT NULL, reason text, snapshot_data text NOT NULL, snapshot_type character varying(255) NOT NULL, status character varying(255) NOT NULL, CONSTRAINT work_order_snapshots_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'APPROVED'::character varying, 'ASSIGNED'::character varying, 'WORK_IN_PROGRESS'::character varying, 'AWAITING_PARTS'::character varying, 'AWAITING_APPROVAL'::character varying, 'READY_FOR_PICKUP'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[]))) );

CREATE TABLE work_order_state_transitions ( created_at timestamp(6) with time zone NOT NULL, transitioned_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, workorder_id uuid NOT NULL, from_status character varying(255) NOT NULL, metadata text, reason text, to_status character varying(255) NOT NULL, transitioned_by character varying(255) NOT NULL, CONSTRAINT work_order_state_transitions_from_status_check CHECK (((from_status)::text = ANY ((ARRAY['DRAFT'::character varying, 'APPROVED'::character varying, 'ASSIGNED'::character varying, 'WORK_IN_PROGRESS'::character varying, 'AWAITING_PARTS'::character varying, 'AWAITING_APPROVAL'::character varying, 'READY_FOR_PICKUP'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[]))), CONSTRAINT work_order_state_transitions_to_status_check CHECK (((to_status)::text = ANY ((ARRAY['DRAFT'::character varying, 'APPROVED'::character varying, 'ASSIGNED'::character varying, 'WORK_IN_PROGRESS'::character varying, 'AWAITING_PARTS'::character varying, 'AWAITING_APPROVAL'::character varying, 'READY_FOR_PICKUP'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[]))) );

CREATE TABLE work_session ( locked boolean NOT NULL, overlap_override_used boolean NOT NULL, total_duration_seconds integer NOT NULL, approved_at timestamp(6) with time zone, created_at timestamp(6) with time zone NOT NULL, end_at timestamp(6) with time zone, locked_at timestamp(6) with time zone, override_at timestamp(6) with time zone, start_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, location_id uuid NOT NULL, mechanic_id uuid NOT NULL, resource_id uuid, work_order_id uuid NOT NULL, work_order_task_id uuid NOT NULL, work_session_id uuid NOT NULL, approval_notes text, approved_by_user_id character varying(255), overridden_by_user_id character varying(255), override_reason text, status character varying(255) NOT NULL, CONSTRAINT work_session_status_check CHECK (((status)::text = ANY ((ARRAY['IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying])::text[]))) );

CREATE TABLE workorder ( is_reopened boolean, scheduled_date date, approved_at timestamp(6) with time zone, completed_at timestamp(6) with time zone, created_at timestamp(6) with time zone NOT NULL, reopened_at timestamp(6) with time zone, updated_at timestamp(6) with time zone NOT NULL, work_started_at timestamp(6) with time zone, approval_id uuid, customer_id uuid, estimate_id uuid, id uuid NOT NULL, invoice_id uuid, location_id uuid, resource_id uuid, shop_id uuid, vehicle_id uuid, crm_party_id character varying(36), crm_vehicle_id character varying(36), signature_data character varying(100000), approval_notes character varying(255), completed_by character varying(255), completion_notes text, crm_contact_ids text, mechanic_ids text, operational_context_version character varying(255), reopen_reason text, reopened_by character varying(255), required_certifications text, signature_mime_type character varying(255), signer_name character varying(255), status character varying(255), CONSTRAINT workorder_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'APPROVED'::character varying, 'ASSIGNED'::character varying, 'WORK_IN_PROGRESS'::character varying, 'AWAITING_PARTS'::character varying, 'AWAITING_APPROVAL'::character varying, 'READY_FOR_PICKUP'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[]))) );

CREATE TABLE workorder_labor_entry ( hours_worked numeric(10,2) NOT NULL, created_at timestamp(6) with time zone NOT NULL, end_time timestamp(6) without time zone, start_time timestamp(6) without time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, technician_id uuid NOT NULL, workorder_id uuid NOT NULL, workorder_service_id uuid NOT NULL, adjustment_reason text, created_by text NOT NULL, notes text );

CREATE TABLE workorder_part ( customer_denial_acknowledged boolean, declined boolean, is_emergency_safety boolean, is_substituted boolean NOT NULL, line_total numeric(19,2), photo_not_possible boolean, quantity numeric(19,4), quantity_consumed numeric(19,4), quantity_issued numeric(19,4), quantity_returned numeric(19,4), unit_price numeric(19,2), created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, change_request_id uuid, id uuid NOT NULL, non_inventory_product_entity_id uuid, origin_estimate_item_id uuid, original_product_id uuid, product_entity_id uuid, work_order_id uuid, work_order_service_id uuid, price_lock_status character varying(20) NOT NULL, tax_code character varying(50), description character varying(500), emergency_notes text, photo_evidence_url character varying(255), status character varying(255), CONSTRAINT ck_workorder_part_has_reference CHECK (((work_order_service_id IS NOT NULL) OR (work_order_id IS NOT NULL))), CONSTRAINT workorder_part_price_lock_status_check CHECK (((price_lock_status)::text = ANY ((ARRAY['LOCKED'::character varying, 'UNLOCKED'::character varying])::text[]))), CONSTRAINT workorder_part_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING_APPROVAL'::character varying, 'OPEN'::character varying, 'READY_TO_EXECUTE'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[]))) );

CREATE TABLE workorder_part_adjustment_event ( quantity_adjustment numeric(19,4) NOT NULL, created_at timestamp(6) with time zone NOT NULL, performed_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, original_part_id uuid NOT NULL, substituted_with_part_id uuid, workorder_id uuid NOT NULL, adjustment_type character varying(30) NOT NULL, reason character varying(1000) NOT NULL, notes text, performed_by character varying(255) NOT NULL );

CREATE TABLE workorder_part_substitution ( created_at timestamp(6) with time zone NOT NULL, selected_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, original_product_id uuid NOT NULL, substitute_product_id uuid NOT NULL, substitution_id uuid NOT NULL, workorder_id uuid NOT NULL, workorder_line_item_id uuid NOT NULL, original_part_number_snapshot character varying(255), pricing_snapshot text, reason_code character varying(255), selected_by character varying(255) NOT NULL, status character varying(255), substitute_part_number_snapshot character varying(255), CONSTRAINT workorder_part_substitution_status_check CHECK (((status)::text = ANY ((ARRAY['APPLIED'::character varying, 'REVERSED'::character varying, 'SUPERSEDED'::character varying])::text[]))) );

CREATE TABLE workorder_part_usage_event ( quantity numeric(19,4) NOT NULL, created_at timestamp(6) with time zone NOT NULL, performed_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, workorder_id uuid NOT NULL, workorder_part_id uuid NOT NULL, event_type character varying(20) NOT NULL, notes text, performed_by character varying(255) NOT NULL );

CREATE TABLE workorder_service ( customer_denial_acknowledged boolean, declined boolean, is_emergency_safety boolean, line_total numeric(19,2), photo_not_possible boolean, quantity numeric(19,4), unit_price numeric(19,2), created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, change_request_id uuid, id uuid NOT NULL, origin_estimate_item_id uuid, service_entity_id uuid, technician_id uuid, work_order_id uuid, tax_code character varying(50), description character varying(500), emergency_notes text, photo_evidence_url character varying(255), status character varying(255), CONSTRAINT workorder_service_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING_APPROVAL'::character varying, 'OPEN'::character varying, 'READY_TO_EXECUTE'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[]))) );

ALTER TABLE approval_configuration ADD CONSTRAINT approval_configuration_pkey PRIMARY KEY (id);

ALTER TABLE approval_record ADD CONSTRAINT approval_record_pkey PRIMARY KEY (id);

ALTER TABLE audit_events ADD CONSTRAINT audit_events_pkey PRIMARY KEY (id);

ALTER TABLE break_segment ADD CONSTRAINT break_segment_pkey PRIMARY KEY (break_segment_id);

ALTER TABLE change_request ADD CONSTRAINT change_request_pkey PRIMARY KEY (id);

ALTER TABLE customer ADD CONSTRAINT customer_pkey PRIMARY KEY (id);

ALTER TABLE estimate_item ADD CONSTRAINT estimate_item_pkey PRIMARY KEY (id);

ALTER TABLE estimate ADD CONSTRAINT estimate_location_id_estimate_number_key UNIQUE (location_id, estimate_number);

ALTER TABLE estimate ADD CONSTRAINT estimate_pkey PRIMARY KEY (id);

ALTER TABLE estimate_sequence ADD CONSTRAINT estimate_sequence_last_sequence_number_key UNIQUE (last_sequence_number);

ALTER TABLE estimate_sequence ADD CONSTRAINT estimate_sequence_pkey PRIMARY KEY (id);

ALTER TABLE estimate_snapshot ADD CONSTRAINT estimate_snapshot_pkey PRIMARY KEY (id);

ALTER TABLE idempotency_keys ADD CONSTRAINT idempotency_keys_key_value_key UNIQUE (key_value);

ALTER TABLE idempotency_keys ADD CONSTRAINT idempotency_keys_pkey PRIMARY KEY (id);

ALTER TABLE substitute_audit ADD CONSTRAINT substitute_audit_pkey PRIMARY KEY (audit_id);

ALTER TABLE substitute_link ADD CONSTRAINT substitute_link_pkey PRIMARY KEY (id);

ALTER TABLE technician_assignment ADD CONSTRAINT technician_assignment_pkey PRIMARY KEY (id);

ALTER TABLE time_entry_adjustment ADD CONSTRAINT time_entry_adjustment_pkey PRIMARY KEY (adjustment_id);

ALTER TABLE time_entry ADD CONSTRAINT time_entry_pkey PRIMARY KEY (time_entry_id);

ALTER TABLE travel_segment_adjustment ADD CONSTRAINT travel_segment_adjustment_pkey PRIMARY KEY (adjustment_id);

ALTER TABLE travel_segment ADD CONSTRAINT travel_segment_pkey PRIMARY KEY (travel_segment_id);

ALTER TABLE workorder_part ADD CONSTRAINT uq_workorder_part_origin_estimate_item UNIQUE (origin_estimate_item_id);

ALTER TABLE vehicle ADD CONSTRAINT vehicle_pkey PRIMARY KEY (id);

ALTER TABLE work_order_snapshots ADD CONSTRAINT work_order_snapshots_pkey PRIMARY KEY (id);

ALTER TABLE work_order_state_transitions ADD CONSTRAINT work_order_state_transitions_pkey PRIMARY KEY (id);

ALTER TABLE work_session ADD CONSTRAINT work_session_pkey PRIMARY KEY (work_session_id);

ALTER TABLE workorder_labor_entry ADD CONSTRAINT workorder_labor_entry_pkey PRIMARY KEY (id);

ALTER TABLE workorder_part_adjustment_event ADD CONSTRAINT workorder_part_adjustment_event_pkey PRIMARY KEY (id);

ALTER TABLE workorder_part ADD CONSTRAINT workorder_part_pkey PRIMARY KEY (id);

ALTER TABLE workorder_part_substitution ADD CONSTRAINT workorder_part_substitution_pkey PRIMARY KEY (substitution_id);

ALTER TABLE workorder_part_usage_event ADD CONSTRAINT workorder_part_usage_event_pkey PRIMARY KEY (id);

ALTER TABLE workorder ADD CONSTRAINT workorder_pkey PRIMARY KEY (id);

ALTER TABLE workorder_service ADD CONSTRAINT workorder_service_pkey PRIMARY KEY (id);

CREATE INDEX idx_approval_change_request ON approval_record USING btree (change_request_id);

CREATE INDEX idx_approval_workorder ON approval_record USING btree (workorder_id);

CREATE INDEX idx_break_segment_work_session_id ON break_segment USING btree (work_session_id);

CREATE INDEX idx_labor_service_id ON workorder_labor_entry USING btree (workorder_service_id);

CREATE INDEX idx_labor_start_time ON workorder_labor_entry USING btree (start_time);

CREATE INDEX idx_labor_workorder_id ON workorder_labor_entry USING btree (workorder_id);

CREATE INDEX idx_part_adjustment_original_part ON workorder_part_adjustment_event USING btree (original_part_id);

CREATE INDEX idx_part_adjustment_performed_at ON workorder_part_adjustment_event USING btree (performed_at);

CREATE INDEX idx_part_adjustment_workorder ON workorder_part_adjustment_event USING btree (workorder_id);

CREATE INDEX idx_tea_time_entry_id ON time_entry_adjustment USING btree (time_entry_id);

CREATE INDEX idx_time_entry_person_id ON time_entry USING btree (person_id);

CREATE INDEX idx_time_entry_status ON time_entry USING btree (status);

CREATE INDEX idx_time_entry_work_order_id ON time_entry USING btree (work_order_id);

CREATE INDEX idx_travel_segment_assignment_technician ON travel_segment USING btree (mobile_work_assignment_id, technician_id);

CREATE INDEX idx_travel_segment_mobile_work_assignment_id ON travel_segment USING btree (mobile_work_assignment_id);

CREATE INDEX idx_travel_segment_status ON travel_segment USING btree (status);

CREATE INDEX idx_travel_segment_technician_id ON travel_segment USING btree (technician_id);

CREATE INDEX idx_tsa_travel_segment_id ON travel_segment_adjustment USING btree (travel_segment_id);

CREATE INDEX idx_work_session_mechanic_id ON work_session USING btree (mechanic_id);

CREATE INDEX idx_work_session_status ON work_session USING btree (status);

CREATE INDEX idx_work_session_work_order_id ON work_session USING btree (work_order_id);

CREATE INDEX idx_workorder_part_usage_part_id ON workorder_part_usage_event USING btree (workorder_part_id);

CREATE INDEX idx_workorder_part_usage_performed_at ON workorder_part_usage_event USING btree (performed_at);

CREATE INDEX idx_workorder_part_usage_workorder_id ON workorder_part_usage_event USING btree (workorder_id);

ALTER TABLE workorder_service ADD CONSTRAINT fk4a01gg8t26310lk65ete08o63 FOREIGN KEY (change_request_id) REFERENCES change_request(id);

ALTER TABLE travel_segment_adjustment ADD CONSTRAINT fk5hpprl6nie374vw4sfjkqlpyn FOREIGN KEY (travel_segment_id) REFERENCES travel_segment(travel_segment_id);

ALTER TABLE workorder_part ADD CONSTRAINT fk94qv1vxmkuiekgmg1erdnpe5 FOREIGN KEY (work_order_service_id) REFERENCES workorder_service(id);

ALTER TABLE workorder_service ADD CONSTRAINT fkaj6fpcjjya5btf9pn1op79q6o FOREIGN KEY (origin_estimate_item_id) REFERENCES estimate_item(id);

ALTER TABLE workorder_part_substitution ADD CONSTRAINT fkbvb6pp916jq24pgovw454ybdi FOREIGN KEY (workorder_id) REFERENCES workorder(id);

ALTER TABLE substitute_audit ADD CONSTRAINT fkc0m2tq5577psgcn7p2h1bgnla FOREIGN KEY (link_id) REFERENCES substitute_link(id);

ALTER TABLE travel_segment ADD CONSTRAINT fkc6jnut568pykihsm5xoeurv43 FOREIGN KEY (work_order_id) REFERENCES workorder(id);

ALTER TABLE technician_assignment ADD CONSTRAINT fkcgmswpinjklddl0xlogckv5w FOREIGN KEY (workorder_id) REFERENCES workorder(id);

ALTER TABLE workorder_part ADD CONSTRAINT fkdumywpq9vshkdoo6uv5f4gkqs FOREIGN KEY (work_order_id) REFERENCES workorder(id);

ALTER TABLE estimate_snapshot ADD CONSTRAINT fke8hvol4ak53820p7kr5croac7 FOREIGN KEY (estimate_id) REFERENCES estimate(id);

ALTER TABLE workorder ADD CONSTRAINT fkfa257ydap22tdpq1yyihpojnc FOREIGN KEY (estimate_id) REFERENCES estimate(id);

ALTER TABLE workorder_part_adjustment_event ADD CONSTRAINT fkfqbpwls7v6l7lyf5nh7ih0h4b FOREIGN KEY (original_part_id) REFERENCES workorder_part(id);

ALTER TABLE approval_record ADD CONSTRAINT fkgd3xkwj49m4t6pwh8qjfxfs6r FOREIGN KEY (change_request_id) REFERENCES change_request(id);

ALTER TABLE work_order_snapshots ADD CONSTRAINT fkhi852crcj74eotgvl2x28o4cp FOREIGN KEY (workorder_id) REFERENCES workorder(id);

ALTER TABLE workorder_part ADD CONSTRAINT fki14fyu6qumnimg5vin4m04qbd FOREIGN KEY (change_request_id) REFERENCES change_request(id);

ALTER TABLE work_order_state_transitions ADD CONSTRAINT fki3vup0m7mrkl8kw08fh7y6daj FOREIGN KEY (workorder_id) REFERENCES workorder(id);

ALTER TABLE change_request ADD CONSTRAINT fkirsjwh8x8m1cc4of04reipqaj FOREIGN KEY (workorder_id) REFERENCES workorder(id);

ALTER TABLE estimate_item ADD CONSTRAINT fkkjmwvrhwjc6f99v2mlscqw46q FOREIGN KEY (estimate_id) REFERENCES estimate(id);

ALTER TABLE time_entry ADD CONSTRAINT fklc08fglgxfw0o7yqgaqyrk3ii FOREIGN KEY (work_order_id) REFERENCES workorder(id);

ALTER TABLE time_entry_adjustment ADD CONSTRAINT fkls70vixxyk0xncsnq2x2jphjq FOREIGN KEY (time_entry_id) REFERENCES time_entry(time_entry_id);

ALTER TABLE workorder_part_substitution ADD CONSTRAINT fkm05n3cjven7ckuqpbgyh4nd37 FOREIGN KEY (workorder_line_item_id) REFERENCES workorder_part(id);

ALTER TABLE break_segment ADD CONSTRAINT fkm9qsl4k475w9ifew52crepwyu FOREIGN KEY (work_session_id) REFERENCES work_session(work_session_id);

ALTER TABLE workorder_labor_entry ADD CONSTRAINT fkmm8gqmo8j7lg6mr4ipxplw1s1 FOREIGN KEY (workorder_id) REFERENCES workorder(id);

ALTER TABLE workorder_service ADD CONSTRAINT fknkdeadmqpa6l2mu3bu3k294bn FOREIGN KEY (work_order_id) REFERENCES workorder(id);

ALTER TABLE estimate ADD CONSTRAINT fkp3c0llotl481fyl47simnfwgc FOREIGN KEY (approval_configuration_id) REFERENCES approval_configuration(id);

ALTER TABLE approval_record ADD CONSTRAINT fkphv3bijhfix861adq5waexsoe FOREIGN KEY (workorder_id) REFERENCES workorder(id);

ALTER TABLE workorder_labor_entry ADD CONSTRAINT fkqs91kw9nijnm4x1dwkar59p8e FOREIGN KEY (workorder_service_id) REFERENCES workorder_service(id);

ALTER TABLE work_session ADD CONSTRAINT fkqujp7m2avay32am9oopjtxct7 FOREIGN KEY (work_order_id) REFERENCES workorder(id);

ALTER TABLE workorder_part_usage_event ADD CONSTRAINT fksg9tafcbt297nhibmrsmb9eir FOREIGN KEY (workorder_part_id) REFERENCES workorder_part(id);

ALTER TABLE workorder_part ADD CONSTRAINT fkt4kgc3m4jvn8713h0adc51ik8 FOREIGN KEY (origin_estimate_item_id) REFERENCES estimate_item(id);
