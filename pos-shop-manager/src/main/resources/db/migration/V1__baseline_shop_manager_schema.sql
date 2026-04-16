-- Collapsed baseline for pos-accounting generated from emitted Postgres schema.

-- Source dump: /tmp/pos_shop_manager_baseline_emitted.sql

-- Repeatable seed/data migrations remain separate and are not folded into this file.



SET TIME ZONE 'UTC';



CREATE TABLE appointment ( is_conflict_override boolean NOT NULL, reopen_flag boolean NOT NULL, created_at timestamp(6) with time zone NOT NULL, end_at timestamp(6) with time zone NOT NULL, start_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, appointment_id uuid NOT NULL, crm_customer_id uuid NOT NULL, crm_vehicle_id uuid NOT NULL, location_id uuid NOT NULL, source_type character varying(32), status character varying(32) NOT NULL, assignment_status character varying(64), resource_type character varying(64), cancellation_reason character varying(128), idempotency_key character varying(128), resource_id character varying(128), source_id character varying(128), cancellation_notes character varying(1000), created_by character varying(255), customer_snapshot text, vehicle_snapshot text, workorder_link_ref character varying(255), CONSTRAINT appointment_cancellation_reason_check CHECK (((cancellation_reason)::text = ANY ((ARRAY['CUSTOMER_REQUEST'::character varying, 'TECHNICIAN_UNAVAILABLE'::character varying, 'WEATHER'::character varying, 'VEHICLE_NOT_READY'::character varying, 'OTHER'::character varying])::text[]))), CONSTRAINT appointment_source_type_check CHECK (((source_type)::text = ANY ((ARRAY['ESTIMATE'::character varying, 'WORK_ORDER'::character varying])::text[]))), CONSTRAINT appointment_status_check CHECK (((status)::text = ANY ((ARRAY['SCHEDULED'::character varying, 'CHECKED_IN'::character varying, 'WORK_IN_PROGRESS'::character varying, 'WAITING_FOR_PARTS'::character varying, 'QUALITY_CHECK'::character varying, 'READY_FOR_PICKUP'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying, 'INVOICED'::character varying, 'REOPENED'::character varying])::text[]))) );

CREATE TABLE appointment_audit ( created_at timestamp(6) with time zone NOT NULL, new_end_at timestamp(6) with time zone, new_start_at timestamp(6) with time zone, previous_end_at timestamp(6) with time zone, previous_start_at timestamp(6) with time zone, updated_at timestamp(6) with time zone NOT NULL, appointment_id uuid NOT NULL, audit_id uuid NOT NULL, action character varying(32) NOT NULL, cancellation_reason character varying(128), actor_id character varying(255) NOT NULL, CONSTRAINT appointment_audit_action_check CHECK (((action)::text = ANY ((ARRAY['RESCHEDULED'::character varying, 'CANCELLED'::character varying])::text[]))) );

CREATE TABLE appointment_service_request ( created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, appointment_id uuid NOT NULL, service_entity_id uuid NOT NULL, service_request_id uuid NOT NULL );

CREATE TABLE appointment_status_timeline ( change_timestamp timestamp(6) with time zone NOT NULL, appointment_id uuid NOT NULL, source_event_id uuid NOT NULL, status character varying(50) NOT NULL, CONSTRAINT appointment_status_timeline_status_check CHECK (((status)::text = ANY ((ARRAY['SCHEDULED'::character varying, 'CHECKED_IN'::character varying, 'WORK_IN_PROGRESS'::character varying, 'WAITING_FOR_PARTS'::character varying, 'QUALITY_CHECK'::character varying, 'READY_FOR_PICKUP'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying, 'INVOICED'::character varying, 'REOPENED'::character varying])::text[]))) );

CREATE TABLE assignment ( is_override boolean NOT NULL, version integer NOT NULL, created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, appointment_id uuid NOT NULL, assignment_id uuid NOT NULL, resource_id uuid, status character varying(20) NOT NULL, resource_type character varying(50), notes character varying(500), override_reason character varying(2000), CONSTRAINT assignment_status_check CHECK (((status)::text = ANY ((ARRAY['CONFIRMED'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[]))) );

CREATE TABLE assignment_mechanic ( created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, role character varying(10) NOT NULL, assignment_id uuid NOT NULL, id uuid NOT NULL, mechanic_id uuid NOT NULL, CONSTRAINT assignment_mechanic_role_check CHECK (((role)::text = ANY ((ARRAY['LEAD'::character varying, 'ASSIST'::character varying])::text[]))) );

CREATE TABLE bay ( created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL );

CREATE TABLE certification ( created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, technician_id uuid, ase_code character varying(255), description character varying(255) );

CREATE TABLE hr_integration_log ( created_at timestamp(6) with time zone NOT NULL, processed_at timestamp(6) with time zone, received_at timestamp(6) with time zone, updated_at timestamp(6) with time zone NOT NULL, event_id uuid NOT NULL, error_message character varying(255), event_type character varying(255), payload_hash character varying(255), person_id character varying(255), status character varying(255) );

CREATE TABLE mechanic ( hire_date date, termination_date date, version integer, created_at timestamp(6) with time zone NOT NULL, last_synced_at timestamp(6) with time zone, updated_at timestamp(6) with time zone NOT NULL, mechanic_id uuid NOT NULL, first_name character varying(255), last_name character varying(255), person_id character varying(255) NOT NULL, status character varying(255), CONSTRAINT mechanic_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying, 'ON_LEAVE'::character varying])::text[]))) );

CREATE TABLE mechanic_audit_log ( applied_at timestamp(6) with time zone, created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, event_id uuid, id uuid NOT NULL, after_state text, before_state text, changed_by character varying(255), event_type character varying(255), person_id character varying(255) );

CREATE TABLE mechanic_skill ( certified_date date, expiration_date date, proficiency_level integer, created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, mechanic_id uuid, skill_code character varying(255) );

CREATE TABLE mobile_unit ( created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL );

CREATE TABLE override_record ( created_at timestamp(6) with time zone NOT NULL, override_timestamp timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, appointment_id uuid NOT NULL, override_id uuid NOT NULL, override_reason character varying(2000) NOT NULL, conflict_details text, overridden_by_user_id character varying(255) NOT NULL );

CREATE TABLE reschedule_history ( conflict_overridden boolean NOT NULL, notify_customer boolean NOT NULL, created_at timestamp(6) with time zone NOT NULL, new_end_at timestamp(6) with time zone NOT NULL, new_start_at timestamp(6) with time zone NOT NULL, previous_end_at timestamp(6) with time zone NOT NULL, previous_start_at timestamp(6) with time zone NOT NULL, rescheduled_at timestamp(6) with time zone NOT NULL, appointment_id uuid NOT NULL, reschedule_id uuid NOT NULL, assignment_status character varying(50), notification_status character varying(50), reschedule_reason character varying(50) NOT NULL, reschedule_reason_notes character varying(1000), rescheduled_by character varying(255) NOT NULL, CONSTRAINT reschedule_history_reschedule_reason_check CHECK (((reschedule_reason)::text = ANY ((ARRAY['CUSTOMER_REQUEST'::character varying, 'SHOP_CAPACITY'::character varying, 'EQUIPMENT_ISSUE'::character varying, 'MECHANIC_UNAVAILABLE'::character varying, 'PARTS_DELAY'::character varying, 'WEATHER'::character varying, 'EMERGENCY'::character varying, 'MANAGER_DISCRETION'::character varying, 'OTHER'::character varying])::text[]))) );

CREATE TABLE shop ( created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, timezone character varying(64), address character varying(255), name character varying(255) );

CREATE TABLE shop_audit_entry ( retention_years integer NOT NULL, recorded_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, event_type character varying(64) NOT NULL, appointment_id character varying(128), location_id character varying(128), mechanic_id character varying(128), reason_code character varying(128), workorder_id character varying(128), change_summary_text character varying(1024), reason_notes character varying(2048), actor_user_id character varying(255) NOT NULL, change_patch text, CONSTRAINT shop_audit_entry_event_type_check CHECK (((event_type)::text = ANY ((ARRAY['SCHEDULE_CREATED'::character varying, 'SCHEDULE_UPDATED'::character varying, 'SCHEDULE_CANCELLED'::character varying, 'ASSIGNMENT_CREATED'::character varying, 'ASSIGNMENT_REMOVED'::character varying])::text[]))) );

CREATE TABLE shop_qualification ( created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, shop_id uuid, description character varying(255), name character varying(255) );

CREATE TABLE shop_service ( created_at timestamp(6) with time zone NOT NULL, service_entity_id bigint, updated_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, shop_id uuid );

CREATE TABLE technician ( created_at timestamp(6) with time zone NOT NULL, person_id bigint, updated_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, shop_id uuid );

CREATE TABLE travel_block ( created_at timestamp(6) with time zone NOT NULL, end_time timestamp(6) with time zone NOT NULL, start_time timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, description character varying(255), person_id character varying(255) NOT NULL );

CREATE TABLE work_order_appointment_mapping ( created_at timestamp(6) with time zone NOT NULL, appointment_id uuid NOT NULL, work_order_id uuid NOT NULL, status character varying(50) );

ALTER TABLE appointment_audit ADD CONSTRAINT appointment_audit_pkey PRIMARY KEY (audit_id);

ALTER TABLE appointment ADD CONSTRAINT appointment_pkey PRIMARY KEY (appointment_id);

ALTER TABLE appointment_service_request ADD CONSTRAINT appointment_service_request_pkey PRIMARY KEY (service_request_id);

ALTER TABLE assignment_mechanic ADD CONSTRAINT assignment_mechanic_pkey PRIMARY KEY (id);

ALTER TABLE assignment ADD CONSTRAINT assignment_pkey PRIMARY KEY (assignment_id);

ALTER TABLE bay ADD CONSTRAINT bay_pkey PRIMARY KEY (id);

ALTER TABLE certification ADD CONSTRAINT certification_pkey PRIMARY KEY (id);

ALTER TABLE hr_integration_log ADD CONSTRAINT hr_integration_log_pkey PRIMARY KEY (event_id);

ALTER TABLE mechanic_audit_log ADD CONSTRAINT mechanic_audit_log_pkey PRIMARY KEY (id);

ALTER TABLE mechanic ADD CONSTRAINT mechanic_person_id_key UNIQUE (person_id);

ALTER TABLE mechanic ADD CONSTRAINT mechanic_pkey PRIMARY KEY (mechanic_id);

ALTER TABLE mechanic_skill ADD CONSTRAINT mechanic_skill_pkey PRIMARY KEY (id);

ALTER TABLE mobile_unit ADD CONSTRAINT mobile_unit_pkey PRIMARY KEY (id);

ALTER TABLE override_record ADD CONSTRAINT override_record_pkey PRIMARY KEY (override_id);

ALTER TABLE reschedule_history ADD CONSTRAINT reschedule_history_pkey PRIMARY KEY (reschedule_id);

ALTER TABLE shop_audit_entry ADD CONSTRAINT shop_audit_entry_pkey PRIMARY KEY (id);

ALTER TABLE shop ADD CONSTRAINT shop_pkey PRIMARY KEY (id);

ALTER TABLE shop_qualification ADD CONSTRAINT shop_qualification_pkey PRIMARY KEY (id);

ALTER TABLE shop_service ADD CONSTRAINT shop_service_pkey PRIMARY KEY (id);

ALTER TABLE technician ADD CONSTRAINT technician_pkey PRIMARY KEY (id);

ALTER TABLE travel_block ADD CONSTRAINT travel_block_pkey PRIMARY KEY (id);

ALTER TABLE work_order_appointment_mapping ADD CONSTRAINT work_order_appointment_mapping_pkey PRIMARY KEY (work_order_id);

ALTER TABLE shop_qualification ADD CONSTRAINT fka8rt0876asbtnedcrkt0786mf FOREIGN KEY (shop_id) REFERENCES shop(id);

ALTER TABLE assignment_mechanic ADD CONSTRAINT fkaus27vm5s8ehj0wuxt5os4tys FOREIGN KEY (mechanic_id) REFERENCES mechanic(mechanic_id);

ALTER TABLE work_order_appointment_mapping ADD CONSTRAINT fkbciph65b49of045tgtn4utkqq FOREIGN KEY (appointment_id) REFERENCES appointment(appointment_id);

ALTER TABLE appointment_audit ADD CONSTRAINT fkbgbb40cm1gx85a41mu24efi0r FOREIGN KEY (appointment_id) REFERENCES appointment(appointment_id);

ALTER TABLE override_record ADD CONSTRAINT fkfehiwrkhxkbpfi15gm4v7njef FOREIGN KEY (appointment_id) REFERENCES appointment(appointment_id);

ALTER TABLE assignment_mechanic ADD CONSTRAINT fkhfrfgqn59we30as99qek9tx0j FOREIGN KEY (assignment_id) REFERENCES assignment(assignment_id);

ALTER TABLE reschedule_history ADD CONSTRAINT fkisblp2w3x5bph743ex4jxpj83 FOREIGN KEY (appointment_id) REFERENCES appointment(appointment_id);

ALTER TABLE appointment_service_request ADD CONSTRAINT fkj0cidamfyi4qyhwhg88quh7v7 FOREIGN KEY (appointment_id) REFERENCES appointment(appointment_id);

ALTER TABLE appointment_status_timeline ADD CONSTRAINT fkj0ti8ios12liw73q38d62bp6v FOREIGN KEY (appointment_id) REFERENCES appointment(appointment_id);

ALTER TABLE shop_service ADD CONSTRAINT fkjvyptw2ns1x9j1f2xefonn5mv FOREIGN KEY (shop_id) REFERENCES shop(id);

ALTER TABLE mechanic_skill ADD CONSTRAINT fklmivftejuqatjnknlv7sgnuii FOREIGN KEY (mechanic_id) REFERENCES mechanic(mechanic_id);

ALTER TABLE technician ADD CONSTRAINT fkot51d77oao4rytyqcelts15tf FOREIGN KEY (shop_id) REFERENCES shop(id);

ALTER TABLE certification ADD CONSTRAINT fkp7qq4vd7ljumv5fhk0v3td1u FOREIGN KEY (technician_id) REFERENCES technician(id);

ALTER TABLE assignment ADD CONSTRAINT fkqhmk6tlk80ru7fm7yuqf9lq5e FOREIGN KEY (appointment_id) REFERENCES appointment(appointment_id);
