-- Collapsed baseline for pos-accounting generated from emitted Postgres schema.

-- Source dump: /tmp/pos_customer_baseline_emitted.sql

-- Repeatable seed/data migrations remain separate and are not folded into this file.



SET TIME ZONE 'UTC';



CREATE TABLE commercial_party ( party_type smallint NOT NULL, status smallint NOT NULL, tier smallint NOT NULL, tier_manual_override boolean NOT NULL, created_at timestamp(6) with time zone NOT NULL, tier_assigned_at timestamp(6) with time zone, updated_at timestamp(6) with time zone NOT NULL, customer_id uuid NOT NULL, parent_party_id uuid, billing_terms_id character varying(255), customer_number character varying(255) NOT NULL, display_name character varying(255), email character varying(255), legal_name character varying(255) NOT NULL, party_number character varying(255) NOT NULL, phone_number character varying(255), primary_address character varying(255), tax_id character varying(255), tier_assigned_by character varying(255), CONSTRAINT commercial_party_party_type_check CHECK (((party_type >= 0) AND (party_type <= 2))), CONSTRAINT commercial_party_status_check CHECK (((status >= 0) AND (status <= 3))), CONSTRAINT commercial_party_tier_check CHECK (((tier >= 0) AND (tier <= 5))) );

CREATE TABLE communication_preference ( created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, version bigint NOT NULL, party_id uuid NOT NULL, preference_id uuid NOT NULL, email_preference character varying(20), marketing_preference character varying(20), phone_preference character varying(20), sms_preference character varying(20), update_source character varying(20), preferences_note character varying(1000) );

CREATE TABLE consent_flag ( flag_value boolean, preference_id uuid NOT NULL, flag_name character varying(100) NOT NULL );

CREATE TABLE contact ( active boolean NOT NULL, created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, contact_id uuid NOT NULL, party_id uuid NOT NULL, person_id uuid NOT NULL, email character varying(255), first_name character varying(255) NOT NULL, last_name character varying(255) NOT NULL, phone_number character varying(255) );

CREATE TABLE contact_point ( is_primary boolean NOT NULL, created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, contact_point_id uuid NOT NULL, person_id uuid NOT NULL, contact_type character varying(20) NOT NULL, value character varying(255) NOT NULL, CONSTRAINT contact_point_contact_type_check CHECK (((contact_type)::text = ANY ((ARRAY['EMAIL'::character varying, 'PHONE_MOBILE'::character varying, 'PHONE_HOME'::character varying, 'PHONE_WORK'::character varying, 'FAX'::character varying])::text[]))) );

CREATE TABLE contact_role_assignment ( is_primary boolean NOT NULL, created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, contact_id uuid NOT NULL, customer_account_id uuid NOT NULL, role_name character varying(50) NOT NULL, CONSTRAINT contact_role_assignment_role_name_check CHECK (((role_name)::text = ANY ((ARRAY['BILLING'::character varying, 'PAYMENT_AUTHORIZER'::character varying, 'OPERATIONS'::character varying, 'PRIMARY_BUSINESS_CONTACT'::character varying, 'TECHNICAL'::character varying])::text[]))) );

CREATE TABLE customer_vehicle ( customer_id uuid NOT NULL, vin character varying(255) );

CREATE TABLE merge_audit ( contacts_transferred integer, external_ids_merged integer, vehicles_transferred integer, merged_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, merge_audit_id uuid NOT NULL, merged_by_user_id uuid NOT NULL, source_party_id uuid NOT NULL, survivor_party_id uuid NOT NULL, merge_reason character varying(1000) NOT NULL );

CREATE TABLE party_alias ( created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, source_party_id uuid NOT NULL, target_party_id uuid NOT NULL );

CREATE TABLE party_external_identifier ( party_id uuid NOT NULL, identifier_value character varying(255), system_name character varying(255) NOT NULL );

CREATE TABLE party_note ( created_at timestamp(6) with time zone NOT NULL, note_id uuid NOT NULL, party_id uuid NOT NULL, source_event_id character varying(64) NOT NULL, source_workorder_id character varying(64), note_type character varying(100), note_text character varying(2000) );

CREATE TABLE party_relationship ( effective_end_date date, effective_start_date date NOT NULL, is_primary_billing_contact boolean NOT NULL, created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, created_by uuid, from_party_id uuid NOT NULL, party_relationship_id uuid NOT NULL, to_person_id uuid NOT NULL, updated_by uuid );

CREATE TABLE party_relationship_role ( party_relationship_id uuid NOT NULL, role_type character varying(255) NOT NULL, CONSTRAINT party_relationship_role_role_type_check CHECK (((role_type)::text = ANY ((ARRAY['APPROVER'::character varying, 'BILLING'::character varying, 'PRIMARY_CONTACT'::character varying, 'DRIVER'::character varying, 'TECHNICAL'::character varying])::text[]))) );

CREATE TABLE person_party ( status smallint NOT NULL, tier smallint NOT NULL, tier_manual_override boolean NOT NULL, created_at timestamp(6) with time zone NOT NULL, tier_assigned_at timestamp(6) with time zone, updated_at timestamp(6) with time zone NOT NULL, customer_id uuid NOT NULL, person_id uuid NOT NULL, preferred_contact_method character varying(20) NOT NULL, customer_number character varying(255) NOT NULL, email character varying(255), first_name character varying(255) NOT NULL, last_name character varying(255) NOT NULL, phone_number character varying(255), primary_address character varying(255), tier_assigned_by character varying(255), CONSTRAINT person_party_preferred_contact_method_check CHECK (((preferred_contact_method)::text = ANY ((ARRAY['EMAIL'::character varying, 'PHONE_CALL'::character varying, 'SMS'::character varying, 'NONE'::character varying])::text[]))), CONSTRAINT person_party_status_check CHECK (((status >= 0) AND (status <= 3))), CONSTRAINT person_party_tier_check CHECK (((tier >= 0) AND (tier <= 5))) );

CREATE TABLE processing_log ( created_at timestamp(6) with time zone NOT NULL, processed_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, correlation_id character varying(36), event_id character varying(36) NOT NULL, status character varying(40) NOT NULL, event_type character varying(100) NOT NULL, failure_reason character varying(500), CONSTRAINT processing_log_status_check CHECK (((status)::text = ANY ((ARRAY['SUCCESS'::character varying, 'SKIPPED_DUPLICATE'::character varying, 'SCHEMA_VALIDATION_FAILED'::character varying, 'BUSINESS_RULE_VIOLATION'::character varying])::text[]))) );

CREATE TABLE promotion_counter ( total_usage_count integer NOT NULL, version bigint, counter_id uuid NOT NULL, promotion_id uuid NOT NULL );

CREATE TABLE promotion_redemption ( discount_amount numeric(38,2) NOT NULL, recorded_over_limit boolean, created_at timestamp(6) with time zone NOT NULL, redemption_timestamp timestamp(6) without time zone, customer_id uuid NOT NULL, invoice_id uuid, promotion_id uuid NOT NULL, promotion_redemption_id uuid NOT NULL, workorder_id uuid NOT NULL, discount_type character varying(30) NOT NULL, promotion_code character varying(255) NOT NULL, recorded_by character varying(255), status character varying(30) NOT NULL, CONSTRAINT promotion_redemption_status_check CHECK (((status)::text = ANY ((ARRAY['RECORDED'::character varying, 'RECORDED_OVER_LIMIT'::character varying])::text[]))) );

CREATE TABLE vehicle_projection ( vehicle_year integer, last_event_at timestamp(6) with time zone NOT NULL, vehicle_id uuid NOT NULL, vin character varying(17), color character varying(255), make character varying(255), model character varying(255) );

ALTER TABLE commercial_party ADD CONSTRAINT commercial_party_customer_number_key UNIQUE (customer_number);

ALTER TABLE commercial_party ADD CONSTRAINT commercial_party_party_number_key UNIQUE (party_number);

ALTER TABLE commercial_party ADD CONSTRAINT commercial_party_pkey PRIMARY KEY (customer_id);

ALTER TABLE communication_preference ADD CONSTRAINT communication_preference_party_id_key UNIQUE (party_id);

ALTER TABLE communication_preference ADD CONSTRAINT communication_preference_pkey PRIMARY KEY (preference_id);

ALTER TABLE consent_flag ADD CONSTRAINT consent_flag_pkey PRIMARY KEY (preference_id, flag_name);

ALTER TABLE contact ADD CONSTRAINT contact_pkey PRIMARY KEY (contact_id);

ALTER TABLE contact_point ADD CONSTRAINT contact_point_pkey PRIMARY KEY (contact_point_id);

ALTER TABLE contact_role_assignment ADD CONSTRAINT contact_role_assignment_pkey PRIMARY KEY (contact_id, customer_account_id, role_name);

ALTER TABLE merge_audit ADD CONSTRAINT merge_audit_pkey PRIMARY KEY (merge_audit_id);

ALTER TABLE party_alias ADD CONSTRAINT party_alias_pkey PRIMARY KEY (source_party_id);

ALTER TABLE party_external_identifier ADD CONSTRAINT party_external_identifier_pkey PRIMARY KEY (party_id, system_name);

ALTER TABLE party_note ADD CONSTRAINT party_note_pkey PRIMARY KEY (note_id);

ALTER TABLE party_relationship ADD CONSTRAINT party_relationship_pkey PRIMARY KEY (party_relationship_id);

ALTER TABLE party_relationship_role ADD CONSTRAINT party_relationship_role_pkey PRIMARY KEY (party_relationship_id, role_type);

ALTER TABLE person_party ADD CONSTRAINT person_party_customer_number_key UNIQUE (customer_number);

ALTER TABLE person_party ADD CONSTRAINT person_party_person_id_key UNIQUE (person_id);

ALTER TABLE person_party ADD CONSTRAINT person_party_pkey PRIMARY KEY (customer_id);

ALTER TABLE processing_log ADD CONSTRAINT processing_log_event_id_key UNIQUE (event_id);

ALTER TABLE processing_log ADD CONSTRAINT processing_log_pkey PRIMARY KEY (id);

ALTER TABLE promotion_counter ADD CONSTRAINT promotion_counter_pkey PRIMARY KEY (counter_id);

ALTER TABLE promotion_counter ADD CONSTRAINT promotion_counter_promotion_id_key UNIQUE (promotion_id);

ALTER TABLE promotion_redemption ADD CONSTRAINT promotion_redemption_pkey PRIMARY KEY (promotion_redemption_id);

ALTER TABLE contact_role_assignment ADD CONSTRAINT uk_primary_role_per_account UNIQUE (customer_account_id, role_name, is_primary);

ALTER TABLE promotion_redemption ADD CONSTRAINT uq_promotion_workorder UNIQUE (promotion_id, workorder_id);

ALTER TABLE vehicle_projection ADD CONSTRAINT vehicle_projection_pkey PRIMARY KEY (vehicle_id);

CREATE INDEX idx_comm_pref_party ON communication_preference USING btree (party_id);

CREATE INDEX idx_contact_point_person ON contact_point USING btree (person_id);

CREATE INDEX idx_contact_point_type ON contact_point USING btree (contact_type);

CREATE INDEX idx_contact_point_value ON contact_point USING btree (value);

CREATE INDEX idx_contact_role_account ON contact_role_assignment USING btree (customer_account_id);

CREATE INDEX idx_contact_role_contact ON contact_role_assignment USING btree (contact_id);

CREATE INDEX idx_contact_role_role ON contact_role_assignment USING btree (role_name);

CREATE INDEX idx_merge_audit_merged_at ON merge_audit USING btree (merged_at);

CREATE INDEX idx_merge_audit_source ON merge_audit USING btree (source_party_id);

CREATE INDEX idx_merge_audit_survivor ON merge_audit USING btree (survivor_party_id);

CREATE INDEX idx_party_note_party_id ON party_note USING btree (party_id);

CREATE INDEX idx_party_note_source_event ON party_note USING btree (source_event_id);

CREATE INDEX idx_party_rel_from_party ON party_relationship USING btree (from_party_id);

CREATE INDEX idx_party_rel_primary_billing ON party_relationship USING btree (from_party_id, is_primary_billing_contact);

CREATE INDEX idx_party_rel_to_person ON party_relationship USING btree (to_person_id);

ALTER TABLE party_external_identifier ADD CONSTRAINT fk4t0ae53m82e4t1b16b2auoqxj FOREIGN KEY (party_id) REFERENCES commercial_party(customer_id);

ALTER TABLE contact ADD CONSTRAINT fk7qp8tl77jwe4x93afsgknh2h6 FOREIGN KEY (party_id) REFERENCES commercial_party(customer_id);

ALTER TABLE party_relationship_role ADD CONSTRAINT fk89bwlqs1fhtw1s8ndpf3kf2lr FOREIGN KEY (party_relationship_id) REFERENCES party_relationship(party_relationship_id);

ALTER TABLE party_relationship ADD CONSTRAINT fkftk9y006ra70wvp6kiipg3e5l FOREIGN KEY (to_person_id) REFERENCES person_party(customer_id);

ALTER TABLE consent_flag ADD CONSTRAINT fkloc4qn5pey3x2xm25jb1icc4q FOREIGN KEY (preference_id) REFERENCES communication_preference(preference_id);

ALTER TABLE party_relationship ADD CONSTRAINT fkno0x9fk035wa0dmacpe7dw83k FOREIGN KEY (from_party_id) REFERENCES commercial_party(customer_id);

ALTER TABLE commercial_party ADD CONSTRAINT fkqnadyw5cth6b002hcsauif0ic FOREIGN KEY (parent_party_id) REFERENCES commercial_party(customer_id);

ALTER TABLE contact_point ADD CONSTRAINT fkrb77hgbaofwbqx4eby0wrocb4 FOREIGN KEY (person_id) REFERENCES person_party(customer_id);
