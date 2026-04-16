-- Collapsed baseline for pos-accounting generated from emitted Postgres schema.

-- Source dump: /tmp/pos_catalog_baseline_emitted.sql

-- Repeatable seed/data migrations remain separate and are not folded into this file.



SET TIME ZONE 'UTC';



CREATE TABLE approval_request ( created_at timestamp(6) with time zone NOT NULL, resolved_at timestamp(6) with time zone, assigned_approver_id uuid NOT NULL, id uuid NOT NULL, override_id uuid NOT NULL, status character varying(32) NOT NULL, assignment_strategy character varying(128) NOT NULL, CONSTRAINT approval_request_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying])::text[]))) );

CREATE TABLE catalog_entity ( id uuid NOT NULL, description character varying(255), name character varying(255) );

CREATE TABLE catalog_entity_non_inventory_products ( catalog_entity_id uuid NOT NULL, non_inventory_products_id uuid NOT NULL );

CREATE TABLE catalog_entity_products ( catalog_entity_id uuid NOT NULL, products_id uuid NOT NULL );

CREATE TABLE catalog_entity_services ( catalog_entity_id uuid NOT NULL, services_id uuid NOT NULL );

CREATE TABLE category ( id uuid NOT NULL, name character varying(255) );

CREATE TABLE competitorxreference ( competitor_part_id uuid, id uuid NOT NULL, part_id uuid );

CREATE TABLE cost_tier ( max_quantity integer, min_quantity integer NOT NULL, unit_cost numeric(19,4) NOT NULL, id uuid NOT NULL, supplier_item_cost_id uuid NOT NULL );

CREATE TABLE dimensions ( dimension_value double precision NOT NULL, id uuid NOT NULL, product_id uuid, description character varying(255), dimension_type character varying(255), unit_of_measure character varying(255), CONSTRAINT dimensions_dimension_type_check CHECK (((dimension_type)::text = ANY ((ARRAY['LENGTH'::character varying, 'WIDTH'::character varying, 'HEIGHT'::character varying, 'WEIGHT'::character varying, 'VOLUME'::character varying, 'AREA'::character varying, 'DIAMETER'::character varying, 'CIRCUMFERENCE'::character varying, 'RADIUS'::character varying, 'TEMPERATURE'::character varying, 'TIME'::character varying, 'SPEED'::character varying, 'PRESSURE'::character varying, 'FORCE'::character varying, 'ENERGY'::character varying, 'POWER'::character varying, 'FREQUENCY'::character varying, 'ANGLE'::character varying, 'DATA_SIZE'::character varying, 'CURRENT'::character varying, 'VOLTAGE'::character varying, 'RESISTANCE'::character varying])::text[]))) );

CREATE TABLE guardrail_policy ( auto_approval_threshold_percent numeric(10,4) NOT NULL, max_discount_percent numeric(10,4) NOT NULL, min_margin_percent numeric(10,4) NOT NULL, created_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, scope_id uuid NOT NULL, scope character varying(32) NOT NULL, CONSTRAINT guardrail_policy_scope_check CHECK (((scope)::text = ANY ((ARRAY['LOCATION'::character varying, 'REGION'::character varying])::text[]))) );

CREATE TABLE item_cost ( average_cost numeric(19,4), last_cost numeric(19,4), qty_on_hand numeric(19,4) NOT NULL, standard_cost numeric(19,4), created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, item_cost_id uuid NOT NULL, item_id uuid NOT NULL );

CREATE TABLE item_cost_audit ( new_value numeric(19,4), old_value numeric(19,4), audit_timestamp timestamp(6) with time zone NOT NULL, audit_id uuid NOT NULL, item_id uuid NOT NULL, actor character varying(255) NOT NULL, change_source_id character varying(255) NOT NULL, change_source_type character varying(255) NOT NULL, cost_type_changed character varying(255) NOT NULL, reason_code character varying(255), CONSTRAINT item_cost_audit_change_source_type_check CHECK (((change_source_type)::text = ANY ((ARRAY['MANUAL'::character varying, 'PURCHASE_ORDER'::character varying])::text[]))), CONSTRAINT item_cost_audit_cost_type_changed_check CHECK (((cost_type_changed)::text = ANY ((ARRAY['STANDARD'::character varying, 'LAST'::character varying, 'AVERAGE'::character varying])::text[]))) );

CREATE TABLE location_price_override ( base_price numeric(19,4) NOT NULL, cost numeric(19,4), discount_percent numeric(10,4) NOT NULL, margin_percent numeric(10,4), override_price numeric(19,4) NOT NULL, approved_at timestamp(6) with time zone, created_at timestamp(6) with time zone NOT NULL, rejected_at timestamp(6) with time zone, resolved_at timestamp(6) with time zone, version bigint, approved_by_user_id uuid, created_by_user_id uuid NOT NULL, id uuid NOT NULL, location_id uuid NOT NULL, product_id uuid NOT NULL, rejected_by uuid, status character varying(32) NOT NULL, rejection_reason_code character varying(128), rejection_notes character varying(1024), CONSTRAINT location_price_override_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'PENDING_APPROVAL'::character varying, 'REJECTED'::character varying, 'INACTIVE'::character varying])::text[]))) );

CREATE TABLE non_inventory_product ( id uuid NOT NULL, long_description character varying(255), name character varying(255), short_description character varying(255) );

CREATE TABLE oemxreference ( id uuid NOT NULL, oem_part_id uuid, part_id uuid );

CREATE TABLE price_book ( is_default boolean NOT NULL, created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, version bigint, price_book_id uuid NOT NULL, scope_id uuid, status character varying(16) NOT NULL, scope character varying(32) NOT NULL, name character varying(255) NOT NULL, CONSTRAINT price_book_scope_check CHECK (((scope)::text = ANY ((ARRAY['COMPANY_DEFAULT'::character varying, 'LOCATION'::character varying, 'CUSTOMER_TIER'::character varying])::text[]))), CONSTRAINT price_book_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying])::text[]))) );

CREATE TABLE price_book_rule ( priority integer NOT NULL, created_at timestamp(6) with time zone NOT NULL, effective_end_at timestamp(6) with time zone, effective_start_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, version bigint, created_by_user_id uuid NOT NULL, price_book_id uuid NOT NULL, rule_id uuid NOT NULL, target_id uuid, target_type character varying(16) NOT NULL, condition_type character varying(32) NOT NULL, status character varying(32) NOT NULL, condition_value character varying(255), pricing_logic text NOT NULL, CONSTRAINT price_book_rule_condition_type_check CHECK (((condition_type)::text = ANY ((ARRAY['CUSTOMER_TIER'::character varying, 'LOCATION'::character varying, 'NONE'::character varying])::text[]))), CONSTRAINT price_book_rule_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying, 'NOT_APPLICABLE_MISSING_BASE'::character varying])::text[]))), CONSTRAINT price_book_rule_target_type_check CHECK (((target_type)::text = ANY ((ARRAY['SKU'::character varying, 'CATEGORY'::character varying, 'GLOBAL'::character varying])::text[]))) );

CREATE TABLE product ( created_at timestamp(6) with time zone NOT NULL, last_state_changed_at timestamp(6) with time zone, lifecycle_state_effective_at timestamp(6) with time zone, updated_at timestamp(6) with time zone NOT NULL, category_id uuid, id uuid NOT NULL, last_state_changed_by uuid, manufacturer_id uuid, subcategory_id uuid, color character varying(255), country_of_origin character varying(255), description character varying(255), lifecycle_override_reason character varying(255), lifecycle_state character varying(255), long_description character varying(255), manufacturer_brand character varying(255), manufacturer_name character varying(255), manufacturer_part_number character varying(255), manufacturer_warranty character varying(255), material character varying(255), name character varying(255), product_code character varying(255), product_code_type character varying(255), short_description character varying(255), sku character varying(255), status character varying(255), type character varying(255), unit_of_measure character varying(255), upc character varying(255), warranty character varying(255), attributes oid, specifications oid, CONSTRAINT product_lifecycle_state_check CHECK (((lifecycle_state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying, 'DISCONTINUED'::character varying])::text[]))), CONSTRAINT product_product_code_type_check CHECK (((product_code_type)::text = ANY ((ARRAY['UPC'::character varying, 'EAN'::character varying])::text[]))), CONSTRAINT product_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying])::text[]))) );

CREATE TABLE product_images ( product_id uuid NOT NULL, image_url character varying(255) );

CREATE TABLE product_msrp ( amount numeric(19,4) NOT NULL, currency character varying(3) NOT NULL, effective_end_date date, effective_start_date date NOT NULL, created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, version bigint, msrp_id uuid NOT NULL, product_id uuid NOT NULL, updated_by uuid );

CREATE TABLE product_replacement ( priority_order integer NOT NULL, created_at timestamp(6) with time zone NOT NULL, deleted_at timestamp(6) with time zone, effective_at timestamp(6) with time zone, updated_at timestamp(6) with time zone NOT NULL, original_product_id uuid NOT NULL, replacement_id uuid NOT NULL, replacement_product_id uuid NOT NULL, notes character varying(255) );

CREATE TABLE service ( id uuid NOT NULL, long_description character varying(255), name character varying(255), short_description character varying(255) );

CREATE TABLE subcategory ( id uuid NOT NULL, name character varying(255) );

CREATE TABLE supplier_item_cost ( base_cost numeric(19,4), currency_code character varying(3) NOT NULL, created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, item_id uuid NOT NULL, supplier_id uuid NOT NULL );

CREATE TABLE uom_conversion ( conversion_factor numeric(20,6) NOT NULL, is_active boolean NOT NULL, created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, created_by character varying(255), from_uom_code character varying(255) NOT NULL, to_uom_code character varying(255) NOT NULL );

ALTER TABLE approval_request ADD CONSTRAINT approval_request_pkey PRIMARY KEY (id);

ALTER TABLE catalog_entity_non_inventory_products ADD CONSTRAINT catalog_entity_non_inventory_prod_non_inventory_products_id_key UNIQUE (non_inventory_products_id);

ALTER TABLE catalog_entity ADD CONSTRAINT catalog_entity_pkey PRIMARY KEY (id);

ALTER TABLE catalog_entity_products ADD CONSTRAINT catalog_entity_products_products_id_key UNIQUE (products_id);

ALTER TABLE catalog_entity_services ADD CONSTRAINT catalog_entity_services_services_id_key UNIQUE (services_id);

ALTER TABLE category ADD CONSTRAINT category_pkey PRIMARY KEY (id);

ALTER TABLE competitorxreference ADD CONSTRAINT competitorxreference_pkey PRIMARY KEY (id);

ALTER TABLE cost_tier ADD CONSTRAINT cost_tier_pkey PRIMARY KEY (id);

ALTER TABLE dimensions ADD CONSTRAINT dimensions_pkey PRIMARY KEY (id);

ALTER TABLE guardrail_policy ADD CONSTRAINT guardrail_policy_pkey PRIMARY KEY (id);

ALTER TABLE item_cost_audit ADD CONSTRAINT item_cost_audit_pkey PRIMARY KEY (audit_id);

ALTER TABLE item_cost ADD CONSTRAINT item_cost_item_id_key UNIQUE (item_id);

ALTER TABLE item_cost ADD CONSTRAINT item_cost_pkey PRIMARY KEY (item_cost_id);

ALTER TABLE location_price_override ADD CONSTRAINT location_price_override_pkey PRIMARY KEY (id);

ALTER TABLE non_inventory_product ADD CONSTRAINT non_inventory_product_pkey PRIMARY KEY (id);

ALTER TABLE oemxreference ADD CONSTRAINT oemxreference_pkey PRIMARY KEY (id);

ALTER TABLE price_book ADD CONSTRAINT price_book_pkey PRIMARY KEY (price_book_id);

ALTER TABLE price_book_rule ADD CONSTRAINT price_book_rule_pkey PRIMARY KEY (rule_id);

ALTER TABLE product_msrp ADD CONSTRAINT product_msrp_pkey PRIMARY KEY (msrp_id);

ALTER TABLE product ADD CONSTRAINT product_pkey PRIMARY KEY (id);

ALTER TABLE product_replacement ADD CONSTRAINT product_replacement_pkey PRIMARY KEY (replacement_id);

ALTER TABLE service ADD CONSTRAINT service_pkey PRIMARY KEY (id);

ALTER TABLE subcategory ADD CONSTRAINT subcategory_pkey PRIMARY KEY (id);

ALTER TABLE supplier_item_cost ADD CONSTRAINT supplier_item_cost_pkey PRIMARY KEY (id);

ALTER TABLE product ADD CONSTRAINT uk_product_manufacturer_mpn UNIQUE (manufacturer_id, manufacturer_part_number);

ALTER TABLE product ADD CONSTRAINT uk_product_sku UNIQUE (sku);

ALTER TABLE supplier_item_cost ADD CONSTRAINT uk_supplier_item_cost_supplier_item UNIQUE (supplier_id, item_id);

ALTER TABLE uom_conversion ADD CONSTRAINT uom_conversion_pkey PRIMARY KEY (id);

CREATE INDEX idx_item_cost_audit_item_id ON item_cost_audit USING btree (item_id);

CREATE INDEX idx_item_cost_audit_timestamp ON item_cost_audit USING btree (audit_timestamp);

CREATE INDEX idx_product_manufacturer_part_number ON product USING btree (manufacturer_part_number);

ALTER TABLE product ADD CONSTRAINT fk1mtsbur82frn64de7balymq9s FOREIGN KEY (category_id) REFERENCES category(id);

ALTER TABLE product_replacement ADD CONSTRAINT fk23q0f9u2whm28m9fs9n99xb FOREIGN KEY (original_product_id) REFERENCES product(id);

ALTER TABLE cost_tier ADD CONSTRAINT fk39vhrsnjhsyedrqfiajnncnf3 FOREIGN KEY (supplier_item_cost_id) REFERENCES supplier_item_cost(id);

ALTER TABLE catalog_entity_products ADD CONSTRAINT fk476ell5xxschdvutc75nffhja FOREIGN KEY (catalog_entity_id) REFERENCES catalog_entity(id);

ALTER TABLE catalog_entity_services ADD CONSTRAINT fk49m0dx6jwure5r0j0co8seyge FOREIGN KEY (catalog_entity_id) REFERENCES catalog_entity(id);

ALTER TABLE dimensions ADD CONSTRAINT fk7tfbgibw9dikxhdt2kklr2gal FOREIGN KEY (product_id) REFERENCES product(id);

ALTER TABLE price_book_rule ADD CONSTRAINT fkaxidnoonhdakivm7t131al1jg FOREIGN KEY (price_book_id) REFERENCES price_book(price_book_id);

ALTER TABLE product_replacement ADD CONSTRAINT fkbm7akqsv7no4vmqcsskekeg7c FOREIGN KEY (replacement_product_id) REFERENCES product(id);

ALTER TABLE oemxreference ADD CONSTRAINT fke1e54dmkk79ij6eld0f2mdyt0 FOREIGN KEY (part_id) REFERENCES product(id);

ALTER TABLE catalog_entity_non_inventory_products ADD CONSTRAINT fkfawtw55yxh23ywfe0ahses16r FOREIGN KEY (non_inventory_products_id) REFERENCES non_inventory_product(id);

ALTER TABLE competitorxreference ADD CONSTRAINT fkfw2853b0whcpyw6br2rq64hkp FOREIGN KEY (part_id) REFERENCES product(id);

ALTER TABLE oemxreference ADD CONSTRAINT fkgmcb0x8hrpajoiq638668tj5e FOREIGN KEY (oem_part_id) REFERENCES product(id);

ALTER TABLE product_images ADD CONSTRAINT fki8jnqq05sk5nkma3pfp3ylqrt FOREIGN KEY (product_id) REFERENCES product(id);

ALTER TABLE catalog_entity_non_inventory_products ADD CONSTRAINT fkitorsfib7wj1ihj1y7v06gsdm FOREIGN KEY (catalog_entity_id) REFERENCES catalog_entity(id);

ALTER TABLE product ADD CONSTRAINT fkku369nri8u3s17uom8or57trs FOREIGN KEY (subcategory_id) REFERENCES subcategory(id);

ALTER TABLE product_msrp ADD CONSTRAINT fkm2hp6orjpe4idnqs7le7uuenp FOREIGN KEY (product_id) REFERENCES product(id);

ALTER TABLE catalog_entity_products ADD CONSTRAINT fkol0slx2skww65mmbtr82wp8n0 FOREIGN KEY (products_id) REFERENCES product(id);

ALTER TABLE catalog_entity_services ADD CONSTRAINT fkpvoe5tma4j9xfu10rw1j397tx FOREIGN KEY (services_id) REFERENCES service(id);

ALTER TABLE competitorxreference ADD CONSTRAINT fkscuc2tre171k4o6iy0fbxiim3 FOREIGN KEY (competitor_part_id) REFERENCES product(id);
