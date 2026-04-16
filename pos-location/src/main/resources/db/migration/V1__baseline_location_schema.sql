-- Collapsed baseline for pos-accounting generated from emitted Postgres schema.

-- Source dump: /tmp/pos_location_baseline_emitted.sql

-- Repeatable seed/data migrations remain separate and are not folded into this file.



SET TIME ZONE 'UTC';



CREATE TABLE bays ( max_concurrent_vehicles integer NOT NULL, created_at timestamp(6) with time zone NOT NULL, last_modified_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, location_id uuid NOT NULL, bay_type character varying(50) NOT NULL, status character varying(50) NOT NULL, name character varying(255) NOT NULL, normalized_name character varying(255) NOT NULL, service_capability_ids text, skill_requirement_ids text );

CREATE TABLE location ( check_in_buffer_minutes integer, cleanup_buffer_minutes integer, is_active boolean DEFAULT true NOT NULL, created_at timestamp(6) with time zone NOT NULL, responsible_person_id bigint, updated_at timestamp(6) with time zone, version bigint, default_quarantine_location_id uuid, default_staging_location_id uuid, geographical_location_id uuid, id uuid NOT NULL, location_type_id uuid, address_line1 character varying(255), address_line2 character varying(255), city character varying(255), code character varying(255), country character varying(255), holiday_closures text, hr_location_id character varying(255), mailing_address character varying(255), name character varying(255), normalized_name character varying(255), operating_hours text, postal_code character varying(255), state character varying(255), status character varying(255), timezone character varying(255) );

CREATE TABLE location_parent ( created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, child_id uuid, id uuid NOT NULL, parent_id uuid, parent_type character varying(255) NOT NULL, CONSTRAINT location_parent_parent_type_check CHECK (((parent_type)::text = ANY ((ARRAY['HOME_OFFICE'::character varying, 'HEADQUARTERS'::character varying, 'REGION'::character varying, 'DISTRICT'::character varying, 'PHYSICAL'::character varying, 'ORGANIZATIONAL'::character varying, 'FINANCIAL'::character varying, 'SHIPPING'::character varying])::text[]))) );

CREATE TABLE location_type ( created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, description character varying(255), name character varying(255) NOT NULL );

CREATE TABLE mobile_unit_capabilities ( mobile_unit_id uuid NOT NULL, service_capability_id uuid );

CREATE TABLE mobile_unit_coverage_rules ( max_distance numeric(10,2), priority integer NOT NULL, valid_from date, valid_to date, created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, mobile_unit_id uuid NOT NULL, service_area_id uuid, rule_type character varying(20) );

CREATE TABLE mobile_units ( created_at timestamp(6) with time zone, updated_at timestamp(6) with time zone, base_location_id uuid, created_by uuid, id uuid NOT NULL, travel_buffer_policy_id uuid, updated_by uuid, status character varying(20) NOT NULL, name character varying(255) NOT NULL, notes character varying(255) );

CREATE TABLE service_area_postal_codes ( country_code character varying(2) NOT NULL, service_area_id uuid NOT NULL, postal_code character varying(20) NOT NULL );

CREATE TABLE service_areas ( active boolean NOT NULL, created_at timestamp(6) with time zone, updated_at timestamp(6) with time zone, id uuid NOT NULL, description character varying(255), name character varying(255) NOT NULL );

CREATE TABLE service_location_capabilities ( active boolean NOT NULL, created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, code character varying(100) NOT NULL, name character varying(255) NOT NULL );

CREATE TABLE storage_location ( created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, parent_storage_location_id uuid, site_id uuid NOT NULL, status character varying(20) NOT NULL, type character varying(50) NOT NULL, barcode character varying(255), capacity text, name character varying(255) NOT NULL, temperature text, CONSTRAINT storage_location_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying, 'MAINTENANCE'::character varying, 'QUARANTINED'::character varying])::text[]))), CONSTRAINT storage_location_type_check CHECK (((type)::text = ANY ((ARRAY['FLOOR'::character varying, 'SHELF'::character varying, 'BIN'::character varying, 'CAGE'::character varying, 'TRUCK'::character varying])::text[]))) );

CREATE TABLE travel_buffer_policies ( buffer_value numeric(10,2), created_at timestamp(6) with time zone, updated_at timestamp(6) with time zone, id uuid NOT NULL, buffer_type character varying(30), name character varying(255) NOT NULL, notes character varying(255) );

ALTER TABLE bays ADD CONSTRAINT bays_pkey PRIMARY KEY (id);

ALTER TABLE location ADD CONSTRAINT location_code_key UNIQUE (code);

ALTER TABLE location_parent ADD CONSTRAINT location_parent_child_id_parent_id_key UNIQUE (child_id, parent_id);

ALTER TABLE location_parent ADD CONSTRAINT location_parent_child_id_parent_type_key UNIQUE (child_id, parent_type);

ALTER TABLE location_parent ADD CONSTRAINT location_parent_pkey PRIMARY KEY (id);

ALTER TABLE location ADD CONSTRAINT location_pkey PRIMARY KEY (id);

ALTER TABLE location_type ADD CONSTRAINT location_type_name_key UNIQUE (name);

ALTER TABLE location_type ADD CONSTRAINT location_type_pkey PRIMARY KEY (id);

ALTER TABLE mobile_unit_coverage_rules ADD CONSTRAINT mobile_unit_coverage_rules_pkey PRIMARY KEY (id);

ALTER TABLE mobile_units ADD CONSTRAINT mobile_units_pkey PRIMARY KEY (id);

ALTER TABLE service_area_postal_codes ADD CONSTRAINT service_area_postal_codes_pkey PRIMARY KEY (country_code, service_area_id, postal_code);

ALTER TABLE service_areas ADD CONSTRAINT service_areas_name_key UNIQUE (name);

ALTER TABLE service_areas ADD CONSTRAINT service_areas_pkey PRIMARY KEY (id);

ALTER TABLE service_location_capabilities ADD CONSTRAINT service_location_capabilities_code_key UNIQUE (code);

ALTER TABLE service_location_capabilities ADD CONSTRAINT service_location_capabilities_pkey PRIMARY KEY (id);

ALTER TABLE storage_location ADD CONSTRAINT storage_location_pkey PRIMARY KEY (id);

ALTER TABLE travel_buffer_policies ADD CONSTRAINT travel_buffer_policies_name_key UNIQUE (name);

ALTER TABLE travel_buffer_policies ADD CONSTRAINT travel_buffer_policies_pkey PRIMARY KEY (id);

ALTER TABLE bays ADD CONSTRAINT uq_bays_location_normalized_name UNIQUE (location_id, normalized_name);

ALTER TABLE mobile_units ADD CONSTRAINT uq_mobile_units_base_location_name UNIQUE (base_location_id, name);

ALTER TABLE mobile_units ADD CONSTRAINT fk14tp7doak6ilc5rr8b7h0a310 FOREIGN KEY (base_location_id) REFERENCES location(id);

ALTER TABLE service_area_postal_codes ADD CONSTRAINT fk3c529hveuvft9g0lv4m371r12 FOREIGN KEY (service_area_id) REFERENCES service_areas(id);

ALTER TABLE location ADD CONSTRAINT fk3s2oh3hdbn04p992i3hgm4b8m FOREIGN KEY (default_quarantine_location_id) REFERENCES storage_location(id);

ALTER TABLE location ADD CONSTRAINT fk47pk9c008u23eol1yijbo817 FOREIGN KEY (location_type_id) REFERENCES location_type(id);

ALTER TABLE location_parent ADD CONSTRAINT fk50f6la4k5hmkxyp0k43kpyuo4 FOREIGN KEY (parent_id) REFERENCES location(id);

ALTER TABLE mobile_unit_coverage_rules ADD CONSTRAINT fk7i547xh8luwxemsk1t2p80nt0 FOREIGN KEY (mobile_unit_id) REFERENCES mobile_units(id);

ALTER TABLE location_parent ADD CONSTRAINT fkdfqix68jubwoxvt2w9r5y1h7x FOREIGN KEY (child_id) REFERENCES location(id);

ALTER TABLE storage_location ADD CONSTRAINT fke0bren9wet4i4n30117psgccp FOREIGN KEY (site_id) REFERENCES location(id);

ALTER TABLE mobile_unit_coverage_rules ADD CONSTRAINT fke6a0yvl9wepf1n856h9o2srqc FOREIGN KEY (service_area_id) REFERENCES service_areas(id);

ALTER TABLE bays ADD CONSTRAINT fkekpk5e52w12cxo45fjxes8tqu FOREIGN KEY (location_id) REFERENCES location(id);

ALTER TABLE location ADD CONSTRAINT fkg7jreoag8htlr5iwpt30cid0b FOREIGN KEY (default_staging_location_id) REFERENCES storage_location(id);

ALTER TABLE mobile_unit_capabilities ADD CONSTRAINT fkm9pb1jh1id64ynss96aghvngl FOREIGN KEY (mobile_unit_id) REFERENCES mobile_units(id);

ALTER TABLE storage_location ADD CONSTRAINT fkoywv5iasqrj36rvxkfehsnjv5 FOREIGN KEY (parent_storage_location_id) REFERENCES storage_location(id);
