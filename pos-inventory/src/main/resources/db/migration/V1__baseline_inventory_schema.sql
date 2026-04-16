-- Collapsed baseline for pos-accounting generated from emitted Postgres schema.

-- Source dump: /tmp/pos_inventory_baseline_emitted.sql

-- Repeatable seed/data migrations remain separate and are not folded into this file.



SET TIME ZONE 'UTC';



CREATE TABLE advance_shipping_notice ( expected_arrival_date date, ship_date date, created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, asn_id uuid NOT NULL, po_id uuid NOT NULL, vendor_id uuid NOT NULL, asn_reference_number character varying(255) NOT NULL, created_by character varying(255) NOT NULL, status character varying(255) NOT NULL, updated_by character varying(255), CONSTRAINT advance_shipping_notice_status_check CHECK (((status)::text = ANY ((ARRAY['LOADED'::character varying, 'READY_FOR_RECEIPT'::character varying, 'PARTIALLY_RECEIVED'::character varying, 'FULLY_RECEIVED'::character varying, 'CANCELLED'::character varying])::text[]))) );

CREATE TABLE approval_threshold_config ( active boolean NOT NULL, percentage_variance_threshold numeric(5,2) NOT NULL, unit_variance_threshold integer NOT NULL, value_variance_threshold numeric(19,2) NOT NULL, created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, config_id uuid NOT NULL, approval_tier character varying(255) NOT NULL, CONSTRAINT approval_threshold_config_approval_tier_check CHECK (((approval_tier)::text = ANY ((ARRAY['TIER_1_MANAGER'::character varying, 'TIER_2_DIRECTOR'::character varying])::text[]))) );

CREATE TABLE asn_line ( quantity_received numeric(18,6), quantity_shipped numeric(18,6) NOT NULL, created_at timestamp(6) with time zone NOT NULL, unit_cost_minor bigint, asn_id uuid NOT NULL, asn_line_id uuid NOT NULL, po_id uuid NOT NULL, po_line_id uuid, lot_number character varying(255), sku character varying(255) NOT NULL, unit_of_measure character varying(255) );

CREATE TABLE count_entry ( actual_quantity integer NOT NULL, expected_quantity integer NOT NULL, recount_sequence_number integer NOT NULL, variance integer NOT NULL, counted_at timestamp(6) with time zone NOT NULL, created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, count_entry_id uuid NOT NULL, cycle_count_task_id uuid NOT NULL, recount_of_count_entry_id uuid, auditor_id character varying(100) NOT NULL );

CREATE TABLE cycle_count_adjustment ( cost_at_time_of_adjustment numeric(19,4) NOT NULL, counted_quantity integer NOT NULL, quantity_change integer NOT NULL, quantity_on_hand_before integer NOT NULL, approved_at timestamp(6) with time zone, created_at timestamp(6) with time zone NOT NULL, posted_at timestamp(6) with time zone, rejected_at timestamp(6) with time zone, updated_at timestamp(6) with time zone NOT NULL, adjustment_id uuid NOT NULL, ledger_entry_id uuid, stock_item_id uuid NOT NULL, rejection_reason character varying(1000), error_message character varying(2000), approved_by_user_id character varying(255), created_by_user_id character varying(255) NOT NULL, reason_code character varying(255) NOT NULL, rejected_by_user_id character varying(255), required_approval_tier character varying(255), status character varying(255) NOT NULL, CONSTRAINT cycle_count_adjustment_required_approval_tier_check CHECK (((required_approval_tier)::text = ANY ((ARRAY['TIER_1_MANAGER'::character varying, 'TIER_2_DIRECTOR'::character varying])::text[]))), CONSTRAINT cycle_count_adjustment_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING_APPROVAL'::character varying, 'AUTO_APPROVED'::character varying, 'APPROVED'::character varying, 'POSTED'::character varying, 'REJECTED'::character varying, 'FAILED'::character varying])::text[]))) );

CREATE TABLE cycle_count_plan ( scheduled_date date NOT NULL, created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, location_id uuid NOT NULL, plan_id uuid NOT NULL, status character varying(50) NOT NULL, created_by character varying(255) NOT NULL, plan_name character varying(255), CONSTRAINT cycle_count_plan_status_check CHECK (((status)::text = ANY ((ARRAY['PLANNED'::character varying, 'STARTED'::character varying, 'COMPLETED_PENDING_APPROVAL'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying, 'CANCELLED'::character varying])::text[]))) );

CREATE TABLE cycle_count_plan_zone ( plan_id uuid NOT NULL, zone_id uuid );

CREATE TABLE cycle_count_task ( count_entries_count integer NOT NULL, expected_quantity integer NOT NULL, created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, latest_count_entry_id uuid, task_id uuid NOT NULL, status character varying(50) NOT NULL, auditor_id character varying(100) NOT NULL, bin_location character varying(100) NOT NULL, item_sku character varying(100) NOT NULL, item_description character varying(500), CONSTRAINT cycle_count_task_status_check CHECK (((status)::text = ANY ((ARRAY['ASSIGNED'::character varying, 'COUNTED_PENDING_REVIEW'::character varying, 'REQUIRES_INVESTIGATION'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying])::text[]))) );

CREATE TABLE distributor_feed_exception ( created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, raw_payload character varying(4000) NOT NULL, distributor_id character varying(255) NOT NULL, distributor_sku character varying(255), reason character varying(255) NOT NULL, CONSTRAINT distributor_feed_exception_reason_check CHECK (((reason)::text = ANY ((ARRAY['SKU_UNMAPPED'::character varying, 'LEAD_TIME_UNPARSABLE'::character varying, 'REGION_UNMAPPED'::character varying, 'REGION_AMBIGUOUS'::character varying, 'MALFORMED_DATA'::character varying])::text[]))) );

CREATE TABLE distributor_normalized_inventory ( lead_time_days_max integer, lead_time_days_min integer, quantity_available integer NOT NULL, created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, product_id uuid NOT NULL, distributor_id character varying(255) NOT NULL, distributor_sku character varying(255) NOT NULL, normalization_policy_version character varying(255) NOT NULL, raw_lead_time character varying(255), raw_ship_from_region character varying(255), ship_from_region_code character varying(255) );

CREATE TABLE goods_receipt ( created_at timestamp(6) with time zone NOT NULL, total_accrued_amount_minor bigint, updated_at timestamp(6) with time zone NOT NULL, asn_id uuid, location_id uuid NOT NULL, po_id uuid NOT NULL, receipt_id uuid NOT NULL, created_by character varying(255) NOT NULL, receipt_number character varying(255) NOT NULL, updated_by character varying(255) );

CREATE TABLE goods_receipt_line ( quantity_received numeric(18,6) NOT NULL, created_at timestamp(6) with time zone NOT NULL, line_accrued_amount_minor bigint, unit_cost_minor bigint NOT NULL, po_line_id uuid, receipt_id uuid NOT NULL, receipt_line_id uuid NOT NULL, lot_number character varying(255), sku character varying(255) NOT NULL );

CREATE TABLE inventory_adjustment_request ( quantity integer NOT NULL, approved_at timestamp(6) with time zone, created_at timestamp(6) with time zone NOT NULL, requested_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, adjustment_request_id uuid NOT NULL, location_id uuid NOT NULL, unit_of_measure character varying(50), reason_code character varying(100) NOT NULL, approved_by_user_id character varying(255), product_sku character varying(255) NOT NULL, requested_by_user_id character varying(255) NOT NULL, status character varying(255) NOT NULL, CONSTRAINT inventory_adjustment_request_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'APPROVED'::character varying, 'REJECTED'::character varying])::text[]))) );

CREATE TABLE inventory_allocation ( allocated_quantity integer NOT NULL, created_at timestamp(6) with time zone NOT NULL, hardened_at timestamp(6) with time zone, updated_at timestamp(6) with time zone NOT NULL, allocation_id uuid NOT NULL, location_id uuid, reservation_id uuid NOT NULL, allocation_state character varying(255) NOT NULL, hardened_by character varying(255), hardened_reason character varying(255), status character varying(255) NOT NULL, CONSTRAINT inventory_allocation_allocation_state_check CHECK (((allocation_state)::text = ANY ((ARRAY['SOFT'::character varying, 'HARD'::character varying])::text[]))), CONSTRAINT inventory_allocation_status_check CHECK (((status)::text = ANY ((ARRAY['ALLOCATED'::character varying, 'PICKED'::character varying, 'RELEASED'::character varying])::text[]))) );

CREATE TABLE inventory_allocation_audit ( created_at timestamp(6) with time zone NOT NULL, occurred_at timestamp(6) with time zone NOT NULL, allocation_audit_id uuid NOT NULL, stock_item_id uuid NOT NULL, new_state character varying(255), previous_state character varying(255), reason_code character varying(255) NOT NULL, trigger_reference_id character varying(255), triggered_by character varying(255) NOT NULL, CONSTRAINT inventory_allocation_audit_reason_code_check CHECK (((reason_code)::text = ANY ((ARRAY['SCHEDULE_CHANGE'::character varying, 'PRIORITY_CHANGE'::character varying, 'PRIORITY_AGED'::character varying, 'MANUAL_OVERRIDE'::character varying, 'STOCK_SHORTAGE'::character varying, 'STOCK_REPLENISHED'::character varying, 'LOCATION_CHANGE'::character varying, 'WORK_ORDER_CANCELLED'::character varying, 'WORK_ORDER_COMPLETED'::character varying, 'SYSTEM_REBALANCE'::character varying])::text[]))) );

CREATE TABLE inventory_ledger_entry ( change_in_quantity integer NOT NULL, quantity_after integer NOT NULL, unit_cost numeric(19,4), created_at timestamp(6) with time zone NOT NULL, "timestamp" timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, adjustment_id uuid, from_location_id uuid, ledger_entry_id uuid NOT NULL, location_id uuid, to_location_id uuid, unit_of_measure character varying(50), reason_code character varying(100), notes character varying(2000), event_type character varying(255) NOT NULL, source_transaction_id character varying(255), stock_item_id character varying(255) NOT NULL, transaction_user_id character varying(255) NOT NULL, CONSTRAINT inventory_ledger_entry_event_type_check CHECK (((event_type)::text = ANY ((ARRAY['GOODS_RECEIPT'::character varying, 'TRANSFER_IN'::character varying, 'PUTAWAY'::character varying, 'RETURN_TO_STOCK'::character varying, 'ADJUSTMENT_IN'::character varying, 'COUNT_VARIANCE_IN'::character varying, 'GOODS_ISSUE'::character varying, 'WORKORDER_CONSUMPTION'::character varying, 'TRANSFER_OUT'::character varying, 'SCRAP_OUT'::character varying, 'ADJUSTMENT_OUT'::character varying, 'COUNT_VARIANCE_OUT'::character varying, 'ADJUST_CYCLE_COUNT'::character varying, 'RESERVATION_CREATED'::character varying, 'RESERVATION_RELEASED'::character varying, 'ALLOCATION_CREATED'::character varying, 'ALLOCATION_RELEASED'::character varying, 'BACKORDER_CREATED'::character varying, 'BACKORDER_RESOLVED'::character varying, 'PICK_TASK_CREATED'::character varying, 'PICK_TASK_COMPLETED'::character varying])::text[]))) );

CREATE TABLE inventory_pick_list ( priority integer NOT NULL, created_at timestamp(6) with time zone NOT NULL, due_at timestamp(6) with time zone, updated_at timestamp(6) with time zone NOT NULL, pick_list_id uuid NOT NULL, workorder_id uuid NOT NULL, status character varying(255) NOT NULL, CONSTRAINT inventory_pick_list_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'READY_TO_PICK'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[]))) );

CREATE TABLE inventory_pick_task ( priority integer NOT NULL, quantity_picked integer NOT NULL, quantity_required integer NOT NULL, sort_order integer NOT NULL, created_at timestamp(6) with time zone NOT NULL, due_at timestamp(6) with time zone, updated_at timestamp(6) with time zone NOT NULL, pick_list_id uuid NOT NULL, pick_task_id uuid NOT NULL, product_id uuid NOT NULL, suggested_location_id uuid, workorder_line_id uuid, sku character varying(255) NOT NULL, status character varying(255) NOT NULL, CONSTRAINT inventory_pick_task_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'NEEDS_REVIEW'::character varying, 'PICKED'::character varying, 'CANCELLED'::character varying])::text[]))) );

CREATE TABLE inventory_reservation ( allocated_quantity integer NOT NULL, priority integer NOT NULL, required_quantity integer NOT NULL, created_at timestamp(6) with time zone NOT NULL, due_date_time timestamp(6) with time zone, schedule_start_time timestamp(6) with time zone, updated_at timestamp(6) with time zone NOT NULL, waiting_since timestamp(6) with time zone, reservation_id uuid NOT NULL, stock_item_id uuid NOT NULL, workorder_line_id uuid NOT NULL, status character varying(255) NOT NULL, CONSTRAINT inventory_reservation_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'PARTIALLY_FULFILLED'::character varying, 'FULFILLED'::character varying, 'BACKORDERED'::character varying, 'CANCELLED'::character varying])::text[]))) );

CREATE TABLE inventory_return ( total_items_returned integer NOT NULL, created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, return_id uuid NOT NULL, workorder_id uuid NOT NULL, return_reason character varying(255) NOT NULL );

CREATE TABLE inventory_return_line ( quantity_returned integer NOT NULL, created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, line_id uuid NOT NULL, return_id uuid NOT NULL, sku_id uuid NOT NULL );

CREATE TABLE inventory_variance ( expected_quantity numeric(10,2) NOT NULL, received_quantity numeric(10,2) NOT NULL, variance_quantity numeric(10,2) NOT NULL, created_at timestamp(6) with time zone NOT NULL, line_id uuid NOT NULL, session_id uuid NOT NULL, variance_id uuid NOT NULL, variance_type character varying(50) NOT NULL, product_id character varying(255) NOT NULL, recorded_by_user_id character varying(255) NOT NULL, CONSTRAINT inventory_variance_variance_type_check CHECK (((variance_type)::text = ANY ((ARRAY['SHORTAGE'::character varying, 'OVERAGE'::character varying])::text[]))) );

CREATE TABLE normalized_availability ( available_qty integer NOT NULL, lead_time_days_max integer, lead_time_days_min integer, min_order_qty integer, pack_size integer NOT NULL, schema_version integer NOT NULL, unit_price_amount numeric(38,2), as_of timestamp(6) with time zone NOT NULL, created_at timestamp(6) with time zone NOT NULL, received_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, manufacturer_id uuid NOT NULL, product_id uuid NOT NULL, manufacturer_part_number character varying(255) NOT NULL, source_location_code character varying(255), unit_price_currency character varying(255), uom character varying(255) NOT NULL );

CREATE TABLE purchase_order ( expected_delivery_date date, po_date date, version_number integer NOT NULL, approval_timestamp timestamp(6) with time zone, created_at timestamp(6) with time zone NOT NULL, grand_total_minor bigint NOT NULL, open_balance_minor bigint, subtotal_minor bigint NOT NULL, tax_minor bigint NOT NULL, updated_at timestamp(6) with time zone NOT NULL, purchase_order_id uuid NOT NULL, ship_to_location_id uuid, vendor_id uuid NOT NULL, approval_notes character varying(255), approver_id character varying(255), comment character varying(255), created_by character varying(255) NOT NULL, currency character varying(255) NOT NULL, encumbrance_ref character varying(255), payment_terms_id character varying(255), po_number character varying(255) NOT NULL, requested_by character varying(255), status character varying(255) NOT NULL, updated_by character varying(255), CONSTRAINT purchase_order_status_check CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'APPROVED'::character varying, 'PARTIALLY_RECEIVED'::character varying, 'FULLY_RECEIVED'::character varying, 'CLOSED'::character varying, 'CANCELLED'::character varying])::text[]))) );

CREATE TABLE purchase_order_line ( line_number integer NOT NULL, open_quantity_decimal numeric(18,6), quantity_decimal numeric(18,6) NOT NULL, created_at timestamp(6) with time zone NOT NULL, line_total_minor bigint NOT NULL, tax_minor bigint, unit_cost_minor bigint NOT NULL, line_id uuid NOT NULL, purchase_order_id uuid NOT NULL, sku_id uuid, description character varying(255) NOT NULL, gl_account_id character varying(255), tax_code_id character varying(255) );

CREATE TABLE putaway_rule ( is_enabled boolean NOT NULL, priority integer NOT NULL, created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, destination_location_id uuid NOT NULL, rule_id uuid NOT NULL, criteria text );

CREATE TABLE putaway_task ( quantity integer NOT NULL, created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, actual_destination_location_id uuid, final_suggested_location_id uuid, original_suggested_location_id uuid, product_id uuid NOT NULL, source_location_id uuid NOT NULL, source_receipt_id uuid NOT NULL, suggested_destination_location_id uuid, task_id uuid NOT NULL, assignee_id character varying(255), fallback_reason character varying(255), status character varying(255) NOT NULL, CONSTRAINT putaway_task_fallback_reason_check CHECK (((fallback_reason)::text = ANY ((ARRAY['DESTINATION_FULL'::character varying, 'UNAVAILABLE'::character varying])::text[]))), CONSTRAINT putaway_task_status_check CHECK (((status)::text = ANY ((ARRAY['UNASSIGNED'::character varying, 'ASSIGNED'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying, 'REQUIRES_LOCATION_SELECTION'::character varying])::text[]))) );

CREATE TABLE receiving_line ( expected_quantity numeric(10,2) NOT NULL, received_quantity numeric(10,2) NOT NULL, created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, line_id uuid NOT NULL, session_id uuid NOT NULL, status character varying(50) NOT NULL, product_id character varying(255) NOT NULL, workorder_id character varying(255), workorder_line_id character varying(255), CONSTRAINT receiving_line_status_check CHECK (((status)::text = ANY ((ARRAY['EXPECTED'::character varying, 'RECEIVED'::character varying, 'RECEIVED_SHORT'::character varying, 'RECEIVED_OVER'::character varying, 'CANCELLED'::character varying])::text[]))) );

CREATE TABLE receiving_session ( created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, session_id uuid NOT NULL, entry_method character varying(50) NOT NULL, source_document_type character varying(50) NOT NULL, status character varying(50) NOT NULL, created_by_user_id character varying(255) NOT NULL, shipment_reference character varying(255), source_document_id character varying(255) NOT NULL, supplier_id character varying(255), CONSTRAINT receiving_session_entry_method_check CHECK (((entry_method)::text = ANY ((ARRAY['MANUAL'::character varying, 'SCAN'::character varying])::text[]))), CONSTRAINT receiving_session_source_document_type_check CHECK (((source_document_type)::text = ANY ((ARRAY['PO'::character varying, 'ASN'::character varying])::text[]))), CONSTRAINT receiving_session_status_check CHECK (((status)::text = ANY ((ARRAY['OPEN'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[]))) );

CREATE TABLE replenishment_policy ( maximum_quantity integer NOT NULL, minimum_quantity integer NOT NULL, created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, location_id uuid NOT NULL, policy_id uuid NOT NULL, itemsku character varying(255) NOT NULL );

CREATE TABLE replenishment_task ( quantity integer NOT NULL, created_at timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, destination_location_id uuid NOT NULL, source_location_id uuid NOT NULL, task_id uuid NOT NULL, assigned_to character varying(255), decision_reason character varying(255), itemsku character varying(255) NOT NULL, sourcing_reason character varying(255), status character varying(255) NOT NULL, trigger_type character varying(255) NOT NULL, CONSTRAINT replenishment_task_decision_reason_check CHECK (((decision_reason)::text = ANY ((ARRAY['BELOW_MIN'::character varying, 'SAFETY_SCAN'::character varying, 'BACKSTOCK_UNAVAILABLE'::character varying])::text[]))), CONSTRAINT replenishment_task_sourcing_reason_check CHECK (((sourcing_reason)::text = ANY ((ARRAY['FEFO'::character varying, 'FIFO'::character varying, 'SUFFICIENT_QTY'::character varying, 'PROXIMITY'::character varying, 'HIGHEST_STOCK'::character varying])::text[]))), CONSTRAINT replenishment_task_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'IN_PROGRESS'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[]))), CONSTRAINT replenishment_task_trigger_type_check CHECK (((trigger_type)::text = ANY ((ARRAY['EVENT'::character varying, 'BATCH'::character varying])::text[]))) );

CREATE TABLE unmapped_manufacturer_part ( occurrence_count integer NOT NULL, created_at timestamp(6) with time zone NOT NULL, first_seen timestamp(6) with time zone NOT NULL, last_seen timestamp(6) with time zone NOT NULL, updated_at timestamp(6) with time zone NOT NULL, id uuid NOT NULL, manufacturer_id uuid NOT NULL, manufacturer_part_number character varying(255) NOT NULL, status character varying(255) NOT NULL, CONSTRAINT unmapped_manufacturer_part_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING_REVIEW'::character varying, 'RESOLVED'::character varying, 'IGNORED'::character varying])::text[]))) );

ALTER TABLE advance_shipping_notice ADD CONSTRAINT advance_shipping_notice_pkey PRIMARY KEY (asn_id);

ALTER TABLE approval_threshold_config ADD CONSTRAINT approval_threshold_config_approval_tier_key UNIQUE (approval_tier);

ALTER TABLE approval_threshold_config ADD CONSTRAINT approval_threshold_config_pkey PRIMARY KEY (config_id);

ALTER TABLE asn_line ADD CONSTRAINT asn_line_pkey PRIMARY KEY (asn_line_id);

ALTER TABLE count_entry ADD CONSTRAINT count_entry_pkey PRIMARY KEY (count_entry_id);

ALTER TABLE cycle_count_adjustment ADD CONSTRAINT cycle_count_adjustment_pkey PRIMARY KEY (adjustment_id);

ALTER TABLE cycle_count_plan ADD CONSTRAINT cycle_count_plan_pkey PRIMARY KEY (plan_id);

ALTER TABLE cycle_count_task ADD CONSTRAINT cycle_count_task_pkey PRIMARY KEY (task_id);

ALTER TABLE distributor_feed_exception ADD CONSTRAINT distributor_feed_exception_pkey PRIMARY KEY (id);

ALTER TABLE distributor_normalized_inventory ADD CONSTRAINT distributor_normalized_invent_distributor_id_distributor_sk_key UNIQUE (distributor_id, distributor_sku);

ALTER TABLE distributor_normalized_inventory ADD CONSTRAINT distributor_normalized_inventory_pkey PRIMARY KEY (id);

ALTER TABLE goods_receipt_line ADD CONSTRAINT goods_receipt_line_pkey PRIMARY KEY (receipt_line_id);

ALTER TABLE goods_receipt ADD CONSTRAINT goods_receipt_pkey PRIMARY KEY (receipt_id);

ALTER TABLE inventory_adjustment_request ADD CONSTRAINT inventory_adjustment_request_pkey PRIMARY KEY (adjustment_request_id);

ALTER TABLE inventory_allocation_audit ADD CONSTRAINT inventory_allocation_audit_pkey PRIMARY KEY (allocation_audit_id);

ALTER TABLE inventory_allocation ADD CONSTRAINT inventory_allocation_pkey PRIMARY KEY (allocation_id);

ALTER TABLE inventory_ledger_entry ADD CONSTRAINT inventory_ledger_entry_pkey PRIMARY KEY (ledger_entry_id);

ALTER TABLE inventory_pick_list ADD CONSTRAINT inventory_pick_list_pkey PRIMARY KEY (pick_list_id);

ALTER TABLE inventory_pick_task ADD CONSTRAINT inventory_pick_task_pkey PRIMARY KEY (pick_task_id);

ALTER TABLE inventory_reservation ADD CONSTRAINT inventory_reservation_pkey PRIMARY KEY (reservation_id);

ALTER TABLE inventory_reservation ADD CONSTRAINT inventory_reservation_workorder_line_id_key UNIQUE (workorder_line_id);

ALTER TABLE inventory_return_line ADD CONSTRAINT inventory_return_line_pkey PRIMARY KEY (line_id);

ALTER TABLE inventory_return ADD CONSTRAINT inventory_return_pkey PRIMARY KEY (return_id);

ALTER TABLE inventory_variance ADD CONSTRAINT inventory_variance_pkey PRIMARY KEY (variance_id);

ALTER TABLE normalized_availability ADD CONSTRAINT normalized_availability_pkey PRIMARY KEY (id);

ALTER TABLE normalized_availability ADD CONSTRAINT normalized_availability_product_id_manufacturer_id_as_of_key UNIQUE (product_id, manufacturer_id, as_of);

ALTER TABLE purchase_order_line ADD CONSTRAINT purchase_order_line_pkey PRIMARY KEY (line_id);

ALTER TABLE purchase_order ADD CONSTRAINT purchase_order_pkey PRIMARY KEY (purchase_order_id);

ALTER TABLE purchase_order ADD CONSTRAINT purchase_order_po_number_key UNIQUE (po_number);

ALTER TABLE putaway_rule ADD CONSTRAINT putaway_rule_pkey PRIMARY KEY (rule_id);

ALTER TABLE putaway_task ADD CONSTRAINT putaway_task_pkey PRIMARY KEY (task_id);

ALTER TABLE receiving_line ADD CONSTRAINT receiving_line_pkey PRIMARY KEY (line_id);

ALTER TABLE receiving_session ADD CONSTRAINT receiving_session_pkey PRIMARY KEY (session_id);

ALTER TABLE replenishment_policy ADD CONSTRAINT replenishment_policy_pkey PRIMARY KEY (policy_id);

ALTER TABLE replenishment_task ADD CONSTRAINT replenishment_task_pkey PRIMARY KEY (task_id);

ALTER TABLE unmapped_manufacturer_part ADD CONSTRAINT unmapped_manufacturer_part_pkey PRIMARY KEY (id);

CREATE INDEX idx_inventory_pick_list_workorder_id ON inventory_pick_list USING btree (workorder_id);

ALTER TABLE purchase_order_line ADD CONSTRAINT fk210t80fsgdi4s4g7tlg9vdgkd FOREIGN KEY (purchase_order_id) REFERENCES purchase_order(purchase_order_id);

ALTER TABLE advance_shipping_notice ADD CONSTRAINT fk6nx0dqi0h7ggdxrs6y4pfjct7 FOREIGN KEY (po_id) REFERENCES purchase_order(purchase_order_id);

ALTER TABLE inventory_variance ADD CONSTRAINT fk7qhyde2xddyg7b38wuw34bnup FOREIGN KEY (session_id) REFERENCES receiving_session(session_id);

ALTER TABLE cycle_count_plan_zone ADD CONSTRAINT fka7pkjwibghg9xne7d4evlw2xh FOREIGN KEY (plan_id) REFERENCES cycle_count_plan(plan_id);

ALTER TABLE goods_receipt_line ADD CONSTRAINT fkaon9osgl04ueeomu6kxsuwe70 FOREIGN KEY (receipt_id) REFERENCES goods_receipt(receipt_id);

ALTER TABLE asn_line ADD CONSTRAINT fkcqfd3khodb6cxkvifl9o6c26h FOREIGN KEY (po_line_id) REFERENCES purchase_order_line(line_id);

ALTER TABLE inventory_allocation ADD CONSTRAINT fkf8heiyt6x4my3d16slljmtmpg FOREIGN KEY (reservation_id) REFERENCES inventory_reservation(reservation_id);

ALTER TABLE count_entry ADD CONSTRAINT fkgx6nwfuegqijtp5rd27jam4fa FOREIGN KEY (cycle_count_task_id) REFERENCES cycle_count_task(task_id);

ALTER TABLE asn_line ADD CONSTRAINT fkhvj0a61es04ch2n43p78gmwgy FOREIGN KEY (po_id) REFERENCES purchase_order(purchase_order_id);

ALTER TABLE goods_receipt ADD CONSTRAINT fkhw1l11tclv7r2klx8gcxg8gu9 FOREIGN KEY (po_id) REFERENCES purchase_order(purchase_order_id);

ALTER TABLE goods_receipt ADD CONSTRAINT fkj69i02eyfgrgsunoxik1o3tci FOREIGN KEY (asn_id) REFERENCES advance_shipping_notice(asn_id);

ALTER TABLE asn_line ADD CONSTRAINT fkj6i7c8gy8yc8rfinkxgy4bj7l FOREIGN KEY (asn_id) REFERENCES advance_shipping_notice(asn_id);

ALTER TABLE inventory_return_line ADD CONSTRAINT fkkhc5hubyi1udeklqreppndc8b FOREIGN KEY (return_id) REFERENCES inventory_return(return_id);

ALTER TABLE inventory_pick_task ADD CONSTRAINT fkmib58mn7rdgd57lb0jcbpeadd FOREIGN KEY (pick_list_id) REFERENCES inventory_pick_list(pick_list_id);

ALTER TABLE inventory_variance ADD CONSTRAINT fkmkk54rbkumqjlple6qmnkba36 FOREIGN KEY (line_id) REFERENCES receiving_line(line_id);

ALTER TABLE count_entry ADD CONSTRAINT fkqoy1if5p70cp8xv9f3j9dwyrd FOREIGN KEY (recount_of_count_entry_id) REFERENCES count_entry(count_entry_id);

ALTER TABLE goods_receipt_line ADD CONSTRAINT fkrlwlvthauoc4gbscp505707d0 FOREIGN KEY (po_line_id) REFERENCES purchase_order_line(line_id);

ALTER TABLE putaway_task ADD CONSTRAINT fktc6klsrgj0umf328yp97fsk0c FOREIGN KEY (source_receipt_id) REFERENCES goods_receipt(receipt_id);

ALTER TABLE receiving_line ADD CONSTRAINT fkxxwobflytqjatjo2119cog9w FOREIGN KEY (session_id) REFERENCES receiving_session(session_id);
