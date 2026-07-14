package com.positivity.securityservice.internal.enums;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Compact permission catalog enum. Each constant maps a permission code string
 * to a permanent bit index for use in JWT perm_bits bitset encoding.
 * <p>
 * Bit indexes are permanent and MUST never be reused or reassigned.
 * To retire a permission, mark it {@code @Deprecated} — never remove or
 * renumber.
 */
@SuppressWarnings("java:S115")
public enum PermissionCode {

    // ── Accounting ──────────────────────────────────────────────────────────
    ACCOUNTING__JE__VIEW(0, "accounting:je:view"),
    ACCOUNTING__JE__CREATE(1, "accounting:je:create"),
    ACCOUNTING__JE__POST(2, "accounting:je:post"),
    ACCOUNTING__AP__VIEW(3, "accounting:ap:view"),
    ACCOUNTING__AP__PAY(4, "accounting:ap:pay"),
    ACCOUNTING__COA__VIEW(5, "accounting:coa:view"),
    ACCOUNTING__COA__CREATE(6, "accounting:coa:create"),
    ACCOUNTING__COA__EDIT(7, "accounting:coa:edit"),
    ACCOUNTING__EVENTS__VIEW(8, "accounting:events:view"),
    ACCOUNTING__EVENTS__SUBMIT(9, "accounting:events:submit"),
    ACCOUNTING__EVENTS__RETRY(10, "accounting:events:retry"),

    // ── Catalog ──────────────────────────────────────────────────────────────
    CATALOG__PRODUCT__VIEW(11, "catalog:product:view"),
    CATALOG__PRODUCT__CREATE(12, "catalog:product:create"),
    CATALOG__PRODUCT__EDIT(13, "catalog:product:edit"),
    CATALOG__PRODUCT__DELETE(14, "catalog:product:delete"),
    PRODUCT__LIFECYCLE__UPDATE(15, "product:lifecycle:update"),
    PRODUCT__LIFECYCLE__OVERRIDE_DISCONTINUED(16, "product:lifecycle:override_discontinued"),
    CATALOG__CATEGORY__VIEW(17, "catalog:category:view"),
    CATALOG__CATEGORY__CREATE(18, "catalog:category:create"),
    CATALOG__CATEGORY__EDIT(19, "catalog:category:edit"),
    CATALOG__CATEGORY__DELETE(20, "catalog:category:delete"),
    CATALOG__SERVICE_TYPE__VIEW(21, "catalog:service_type:view"),
    CATALOG__SERVICE_TYPE__CREATE(22, "catalog:service_type:create"),
    CATALOG__SERVICE_TYPE__EDIT(23, "catalog:service_type:edit"),
    CATALOG__VARIANT__VIEW(24, "catalog:variant:view"),
    CATALOG__VARIANT__CREATE(25, "catalog:variant:create"),
    CATALOG__VARIANT__EDIT(26, "catalog:variant:edit"),

    // ── CRM ──────────────────────────────────────────────────────────────────
    CRM__PARTY__VIEW(27, "crm:party:view"),
    CRM__PARTY__SEARCH(28, "crm:party:search"),
    CRM__PARTY__CREATE(29, "crm:party:create"),
    CRM__PARTY__EDIT(30, "crm:party:edit"),
    CRM__PARTY__DEACTIVATE(31, "crm:party:deactivate"),
    CRM__PARTY__MERGE(32, "crm:party:merge"),
    CRM__CONTACT__VIEW(33, "crm:contact:view"),
    CRM__CONTACT__CREATE(34, "crm:contact:create"),
    CRM__CONTACT__EDIT(35, "crm:contact:edit"),
    CRM__CONTACT__DELETE(36, "crm:contact:delete"),
    CRM__CONTACT_ROLE__VIEW(37, "crm:contact_role:view"),
    CRM__CONTACT_ROLE__ASSIGN(38, "crm:contact_role:assign"),
    CRM__CONTACT_ROLE__REVOKE(39, "crm:contact_role:revoke"),
    CRM__CONTACT_PREFERENCE__VIEW(40, "crm:contact_preference:view"),
    CRM__CONTACT_PREFERENCE__EDIT(41, "crm:contact_preference:edit"),
    CRM__VEHICLE__VIEW(42, "crm:vehicle:view"),
    CRM__VEHICLE__SEARCH(43, "crm:vehicle:search"),
    CRM__VEHICLE__CREATE(44, "crm:vehicle:create"),
    CRM__VEHICLE__EDIT(45, "crm:vehicle:edit"),
    CRM__VEHICLE__DEACTIVATE(46, "crm:vehicle:deactivate"),
    CRM__VEHICLE_PARTY_ASSOCIATION__CREATE(47, "crm:vehicle_party_association:create"),
    CRM__VEHICLE_PARTY_ASSOCIATION__VIEW(48, "crm:vehicle_party_association:view"),
    CRM__VEHICLE_PARTY_ASSOCIATION__EDIT(49, "crm:vehicle_party_association:edit"),
    CRM__VEHICLE_PREFERENCE__VIEW(50, "crm:vehicle_preference:view"),
    CRM__VEHICLE_PREFERENCE__EDIT(51, "crm:vehicle_preference:edit"),
    CRM__PROCESSING_LOG__VIEW(52, "crm:processing_log:view"),
    CRM__SUSPENSE__VIEW(53, "crm:suspense:view"),
    CRM__INTEGRATION__AUDIT(54, "crm:integration:audit"),

    // ── Documents ────────────────────────────────────────────────────────────
    DOCUMENTS__RENDER(55, "documents:render"),

    // ── Inventory ────────────────────────────────────────────────────────────
    INVENTORY__ADJUSTMENT__CREATE(56, "inventory:adjustment:create"),
    INVENTORY__ADJUSTMENT__APPROVE(57, "inventory:adjustment:approve"),
    INVENTORY__ADJUSTMENT__VIEW(58, "inventory:adjustment:view"),
    INVENTORY__PUTAWAY__OVERRIDE_LOCATION_COMPATIBILITY(59, "inventory:putaway:override_location_compatibility"),
    INVENTORY__PUTAWAY__OVERRIDE_LOCATION_CAPACITY(60, "inventory:putaway:override_location_capacity"),
    INVENTORY__CYCLE_COUNT__INITIATE(61, "inventory:cycle_count:initiate"),
    INVENTORY__CYCLE_COUNT__VIEW(62, "inventory:cycle_count:view"),
    INVENTORY__CYCLE_COUNT__COMPLETE(63, "inventory:cycle_count:complete"),
    INVENTORY__ON_HAND__VIEW(64, "inventory:on_hand:view"),
    INVENTORY__ON_HAND__SEARCH(65, "inventory:on_hand:search"),
    INVENTORY__RECEIVING__CREATE(66, "inventory:receiving:create"),
    INVENTORY__RECEIVING__VIEW(67, "inventory:receiving:view"),
    INVENTORY__RECEIVING__COMPLETE(68, "inventory:receiving:complete"),
    INVENTORY__ISSUE__PARTS(69, "inventory:issue:parts"),
    INVENTORY__OVERRIDE__PART_MATCH(70, "inventory:override:part-match"),
    INVENTORY__PURCHASE_ORDER__CREATE(71, "inventory:purchase_order:create"),
    INVENTORY__PURCHASE_ORDER__VIEW(72, "inventory:purchase_order:view"),
    INVENTORY__PURCHASE_ORDER__APPROVE(73, "inventory:purchase_order:approve"),
    INVENTORY__PURCHASE_ORDER__RECEIVE(74, "inventory:purchase_order:receive"),
    INVENTORY__ASN__CREATE(75, "inventory:asn:create"),
    INVENTORY__ASN__VIEW(76, "inventory:asn:view"),
    INVENTORY__GOODS_RECEIPT__CREATE(77, "inventory:goods_receipt:create"),
    INVENTORY__GOODS_RECEIPT__VIEW(78, "inventory:goods_receipt:view"),
    INVENTORY__GOODS_RECEIPT__OVERRIDE(79, "inventory:goods_receipt:override"),
    INVENTORY__ALLOCATIONS__REALLOCATE(80, "inventory:allocations:reallocate"),
    INVENTORY__SHORTAGES__RESOLVE(81, "inventory:shortages:resolve"),

    // ── Invoice ──────────────────────────────────────────────────────────────
    INVOICE__MANAGE(82, "invoice:manage"),
    INVOICE__BILLING_RULES(83, "invoice:billing-rules"),
    INVOICE__FINALIZE(84, "invoice:finalize"),
    INVOICE__FINALIZE__OVERRIDE(346, "invoice:finalize:override"),

    // ── Location ─────────────────────────────────────────────────────────────
    LOCATION__READ(85, "location:read"),
    LOCATION__WRITE(86, "location:write"),
    LOCATION__BAY__READ(87, "location:bay:read"),
    LOCATION__BAY__MANAGE(88, "location:bay:manage"),
    LOCATION__MOBILE_UNIT__READ(89, "location:mobile-unit:read"),
    LOCATION__MOBILE_UNIT__MANAGE(90, "location:mobile-unit:manage"),
    LOCATION__SERVICE_AREA__READ(91, "location:service-area:read"),
    LOCATION__SERVICE_AREA__MANAGE(92, "location:service-area:manage"),
    LOCATION__TRAVEL_BUFFER_POLICY__READ(93, "location:travel-buffer-policy:read"),
    LOCATION__TRAVEL_BUFFER_POLICY__MANAGE(94, "location:travel-buffer-policy:manage"),

    // ── MCP ──────────────────────────────────────────────────────────────────
    MCP__LLM_API__VIEW(95, "mcp:llm_api:view"),
    MCP__LLM_API__CREATE(96, "mcp:llm_api:create"),
    MCP__LLM_API__UPDATE(97, "mcp:llm_api:update"),
    MCP__LLM_API__DELETE(98, "mcp:llm_api:delete"),
    MCP__SYSTEM_PROMPT__VIEW(99, "mcp:system_prompt:view"),
    MCP__SYSTEM_PROMPT__CREATE(100, "mcp:system_prompt:create"),
    MCP__SYSTEM_PROMPT__UPDATE(101, "mcp:system_prompt:update"),
    MCP__SYSTEM_PROMPT__DELETE(102, "mcp:system_prompt:delete"),

    // ── Order ────────────────────────────────────────────────────────────────
    ORDER__ORDER__VIEW(103, "order:order:view"),
    ORDER__ORDER__CREATE(104, "order:order:create"),
    ORDER__ORDER__EDIT(105, "order:order:edit"),
    ORDER__ORDER__CANCEL(106, "order:order:cancel"),
    ORDER__LINE__VIEW(107, "order:line:view"),
    ORDER__LINE__CREATE(108, "order:line:create"),
    ORDER__LINE__EDIT(109, "order:line:edit"),
    ORDER__LINE__DELETE(110, "order:line:delete"),
    ORDER__LINE__ENTER_MANUAL_PRICE(111, "order:line:enter_manual_price"),
    ORDER__PRICE_OVERRIDE__VIEW(112, "order:price_override:view"),
    ORDER__PRICE_OVERRIDE__APPLY(113, "order:price_override:apply"),
    ORDER__PRICE_OVERRIDE__APPROVE(114, "order:price_override:approve"),
    ORDER__PRICE_OVERRIDE__REJECT(115, "order:price_override:reject"),

    // ── People ───────────────────────────────────────────────────────────────
    PEOPLE__EMPLOYEE__VIEW(116, "people:employee:view"),
    PEOPLE__EMPLOYEE__CREATE(117, "people:employee:create"),
    PEOPLE__EMPLOYEE__EDIT(118, "people:employee:edit"),
    PEOPLE__EMPLOYEE__DEACTIVATE(119, "people:employee:deactivate"),
    PEOPLE__ROLE__VIEW(120, "people:role:view"),
    PEOPLE__ROLE__ASSIGN(121, "people:role:assign"),
    PEOPLE__ROLE__REVOKE(122, "people:role:revoke"),
    PEOPLE__SKILL__VIEW(123, "people:skill:view"),
    PEOPLE__SKILL__ASSIGN(124, "people:skill:assign"),
    PEOPLE__SKILL__EDIT(125, "people:skill:edit"),

    // ── Pricing ──────────────────────────────────────────────────────────────
    PRICING__PRICE_BOOK__VIEW(126, "pricing:price_book:view"),
    PRICING__PRICE_BOOK__CREATE(127, "pricing:price_book:create"),
    PRICING__PRICE_BOOK__EDIT(128, "pricing:price_book:edit"),
    PRICING__PRICE_BOOK__DELETE(129, "pricing:price_book:delete"),
    PRICING__NORMALIZATION__VIEW(130, "pricing:normalization:view"),
    PRICING__NORMALIZATION__EDIT(131, "pricing:normalization:edit"),
    PRICING__RESTRICTIONS__VIEW(132, "pricing:restrictions:view"),
    PRICING__RESTRICTIONS__EDIT(133, "pricing:restrictions:edit"),
    PRICING__RULE__VIEW(134, "pricing:rule:view"),
    PRICING__RULE__CREATE(135, "pricing:rule:create"),
    PRICING__RULE__EDIT(136, "pricing:rule:edit"),
    PRICING__RULE__DELETE(137, "pricing:rule:delete"),

    // ── Security ─────────────────────────────────────────────────────────────
    SECURITY__ROLE__VIEW(138, "security:role:view"),
    SECURITY__ROLE__CREATE(139, "security:role:create"),
    SECURITY__ROLE__EDIT(140, "security:role:edit"),
    SECURITY__ROLE__DELETE(141, "security:role:delete"),
    SECURITY__ROLE__ASSIGN(142, "security:role:assign"),
    SECURITY__PERMISSION__VIEW(143, "security:permission:view"),
    SECURITY__PERMISSION__REGISTER(144, "security:permission:register"),
    SECURITY__USER__VIEW(145, "security:user:view"),
    SECURITY__USER__CREATE(146, "security:user:create"),
    SECURITY__USER__EDIT(147, "security:user:edit"),
    SECURITY__USER__DELETE(148, "security:user:delete"),

    // ── Shop ─────────────────────────────────────────────────────────────────
    SHOP__LOCATION__VIEW(149, "shop:location:view"),
    SHOP__LOCATION__CREATE(150, "shop:location:create"),
    SHOP__LOCATION__EDIT(151, "shop:location:edit"),
    SHOP__LOCATION__DEACTIVATE(152, "shop:location:deactivate"),
    SHOP__BAY__VIEW(153, "shop:bay:view"),
    SHOP__BAY__CREATE(154, "shop:bay:create"),
    SHOP__BAY__EDIT(155, "shop:bay:edit"),
    SHOP__BAY__ASSIGN(156, "shop:bay:assign"),
    SHOP__SCHEDULE__VIEW(157, "shop:schedule:view"),
    SHOP__SCHEDULE__EDIT(158, "shop:schedule:edit"),

    // ── Appointments ─────────────────────────────────────────────────────────
    APPOINTMENTS__CREATE(159, "appointments:create"),
    APPOINTMENTS__VIEW(160, "appointments:view"),
    APPOINTMENTS__RESCHEDULE(161, "appointments:reschedule"),
    APPOINTMENTS__CANCEL(162, "appointments:cancel"),

    // ── Tax ──────────────────────────────────────────────────────────────────
    TAX__CALCULATE(163, "tax:calculate"),
    TAX__MODE__VIEW(164, "tax:mode:view"),

    // ── Vehicle Fitment ──────────────────────────────────────────────────────
    VEHICLE_FITMENT__HINT__VIEW(165, "vehicle-fitment:hint:view"),
    VEHICLE_FITMENT__HINT__CREATE(166, "vehicle-fitment:hint:create"),
    VEHICLE_FITMENT__HINT__UPDATE(167, "vehicle-fitment:hint:update"),
    VEHICLE_FITMENT__HINT__DELETE(168, "vehicle-fitment:hint:delete"),
    VEHICLE_FITMENT__CATALOG__VIEW(169, "vehicle-fitment:catalog:view"),

    // ── Vehicle Inventory ────────────────────────────────────────────────────
    VEHICLE_INVENTORY__REGISTRY__VIEW(170, "vehicle-inventory:registry:view"),
    VEHICLE_INVENTORY__REGISTRY__CREATE(171, "vehicle-inventory:registry:create"),
    VEHICLE_INVENTORY__REGISTRY__UPDATE(172, "vehicle-inventory:registry:update"),
    VEHICLE_INVENTORY__REGISTRY__DELETE(173, "vehicle-inventory:registry:delete"),
    VEHICLE_INVENTORY__PREFERENCES__MANAGE(174, "vehicle-inventory:preferences:manage"),
    VEHICLE_INVENTORY__SEARCH__VIEW(175, "vehicle-inventory:search:view"),

    // ── Workorder ────────────────────────────────────────────────────────────
    WORKORDER__WORKORDER__VIEW(176, "workorder:workorder:view"),
    WORKORDER__WORKORDER__CREATE(177, "workorder:workorder:create"),
    WORKORDER__WORKORDER__EDIT(178, "workorder:workorder:edit"),
    WORKORDER__WORKORDER__DELETE(179, "workorder:workorder:delete"),
    WORKORDER__WORKORDER__START(180, "workorder:workorder:start"),
    WORKORDER__WORKORDER__COMPLETE(181, "workorder:workorder:complete"),
    WORKORDER__WORKORDER__REOPEN_COMPLETED(182, "workorder:workorder:reopen_completed"),
    WORKORDER__WORKORDER__APPROVE(183, "workorder:workorder:approve"),
    WORKORDER__ESTIMATE__VIEW(184, "workorder:estimate:view"),
    WORKORDER__ESTIMATE__CREATE(185, "workorder:estimate:create"),
    WORKORDER__ESTIMATE__EDIT(186, "workorder:estimate:edit"),
    WORKORDER__ESTIMATE__DELETE(187, "workorder:estimate:delete"),
    WORKORDER__ESTIMATE__APPROVE(188, "workorder:estimate:approve"),
    WORKORDER__ESTIMATE__DECLINE(189, "workorder:estimate:decline"),
    WORKORDER__ESTIMATE__REOPEN(190, "workorder:estimate:reopen"),
    WORKORDER__ESTIMATE__CALCULATE(191, "workorder:estimate:calculate"),
    WORKORDER__ESTIMATE_ITEM__VIEW(192, "workorder:estimate_item:view"),
    WORKORDER__ESTIMATE_ITEM__ADD(193, "workorder:estimate_item:add"),
    WORKORDER__ESTIMATE_ITEM__EDIT(194, "workorder:estimate_item:edit"),
    WORKORDER__ESTIMATE_ITEM__DELETE(195, "workorder:estimate_item:delete"),
    WORKORDER__ESTIMATE_SNAPSHOT__CREATE(196, "workorder:estimate_snapshot:create"),
    WORKORDER__ESTIMATE_SNAPSHOT__VIEW(197, "workorder:estimate_snapshot:view"),
    WORKORDER__CHANGE_REQUEST__VIEW(198, "workorder:change_request:view"),
    WORKORDER__CHANGE_REQUEST__CREATE(199, "workorder:change_request:create"),
    WORKORDER__CHANGE_REQUEST__APPROVE(200, "workorder:change_request:approve"),
    WORKORDER__CHANGE_REQUEST__DECLINE(201, "workorder:change_request:decline"),
    WORKORDER__CHANGE_REQUEST__EMERGENCY_OVERRIDE(202, "workorder:change_request:emergency_override"),
    WORKORDER__APPROVAL_CONFIG__VIEW(203, "workorder:approval_config:view"),
    WORKORDER__APPROVAL_CONFIG__CREATE(204, "workorder:approval_config:create"),
    WORKORDER__APPROVAL_CONFIG__EDIT(205, "workorder:approval_config:edit"),
    WORKORDER__APPROVAL_CONFIG__DELETE(206, "workorder:approval_config:delete"),
    WORKORDER__INVOICE__VIEW(207, "workorder:invoice:view"),
    WORKORDER__INVOICE__CREATE(208, "workorder:invoice:create"),
    WORKORDER__LABOR__VIEW(209, "workorder:labor:view"),
    WORKORDER__LABOR__ADD(210, "workorder:labor:add"),
    WORKORDER__PARTS__VIEW(211, "workorder:parts:view"),
    WORKORDER__PARTS__ADD(212, "workorder:parts:add"),
    WORKORDER__WIP__VIEW(213, "workorder:wip:view"),
    WORKORDER__WIP__VIEW_ALL_LOCATIONS(214, "workorder:wip:view_all_locations"),

    // ── Security (batch 2) ───────────────────────────────────────────────────
    SECURITY__USER_ACCOUNT_STATE__VIEW(215, "security:user_account_state:view"),
    SECURITY__USER_ACCOUNT_STATE__MANAGE(216, "security:user_account_state:manage"),
    SECURITY__AUDIT__VIEW(217, "security:audit:view"),
    SECURITY__AUDIT__CREATE(218, "security:audit:create"),
    SECURITY__AUTHORIZATION__DECIDE(219, "security:authorization:decide"),
    SECURITY__TOKEN__ISSUE_INTERNAL(220, "security:token:issue_internal"),

    // ── MCP / NLTI runtime ───────────────────────────────────────────────────
    NLTI__REQUEST__SUBMIT(221, "nlti:request:submit"),
    NLTI__REQUEST__READ(222, "nlti:request:read"),
    NLTI__AUDIT__READ(223, "nlti:audit:read"),
    MCP__DOCUMENT__INGEST(224, "mcp:document:ingest"),
    MCP__CHAT__STREAM(225, "mcp:chat:stream"),
    MCP__CHAT__EXECUTE(226, "mcp:chat:execute"),

    // ── Catalog (batch 2) ────────────────────────────────────────────────────
    CATALOG__SUPPLIER_COST__READ(227, "catalog:supplier_cost:read"),
    CATALOG__SUPPLIER_COST__WRITE(228, "catalog:supplier_cost:write"),
    CATALOG__MSRP__READ(229, "catalog:msrp:read"),
    CATALOG__MSRP__WRITE(230, "catalog:msrp:write"),
    CATALOG__PRICE_BOOK__READ(231, "catalog:price_book:read"),
    CATALOG__PRICE_BOOK__WRITE(232, "catalog:price_book:write"),

    // ── People (batch 3) ─────────────────────────────────────────────────────
    PEOPLE__PERSON__VIEW(233, "people:person:view"),
    PEOPLE__PERSON__CREATE(234, "people:person:create"),
    PEOPLE__PERSON__EDIT(235, "people:person:edit"),
    PEOPLE__PERSON__DELETE(236, "people:person:delete"),
    PEOPLE__USER_LINK__VIEW(237, "people:userLink:view"),
    PEOPLE__USER_LINK__WRITE(238, "people:userLink:write"),

    // ── Bulk Import ──────────────────────────────────────────────────────────
    BULK_IMPORT__UPLOAD__EXECUTE(239, "bulkImport:upload:execute"),
    BULK_IMPORT__STATUS__READ(240, "bulkImport:status:read"),

    // ── Accounting (batch 2) ─────────────────────────────────────────────────
    ACCOUNTING__EVENTS__REPROCESS(241, "accounting:events:reprocess"),
    ACCOUNTING__EXPORT__REQUEST(242, "accounting:export:request"),
    ACCOUNTING__EXPORT__VIEW(243, "accounting:export:view"),

    // ── CRM (batch 2) ────────────────────────────────────────────────────────
    CRM__BILLING_RULES__EDIT(244, "crm:billing_rules:edit"),

    // ── Pricing (batch 2) ────────────────────────────────────────────────────
    PRICING__BASE_PRICE__CREATE(245, "pricing:base_price:create"),

    // ── Inventory (batch 2) ──────────────────────────────────────────────────
    INVENTORY__LEDGER__VIEW(246, "inventory:ledger:view"),
    INVENTORY__LOCATION__ADMIN(247, "inventory:location:admin"),
    INVENTORY__LOCATION__VIEW(248, "inventory:location:view"),
    INVENTORY__PICK_LIST__CREATE(249, "inventory:pick_list:create"),
    INVENTORY__PICK_LIST__EXECUTE(250, "inventory:pick_list:execute"),
    INVENTORY__PICK_LIST__VIEW(251, "inventory:pick_list:view"),
    INVENTORY__PUTAWAY__CLAIM(252, "inventory:putaway:claim"),
    INVENTORY__PUTAWAY__EXECUTE(253, "inventory:putaway:execute"),
    INVENTORY__PUTAWAY__GENERATE(254, "inventory:putaway:generate"),
    INVENTORY__PUTAWAY__VIEW(255, "inventory:putaway:view"),
    INVENTORY__RETURN__VIEW(256, "inventory:return:view"),
    INVENTORY__RETURN__WRITE(257, "inventory:return:write"),
    INVENTORY__SHORTAGE__RESOLVE(258, "inventory:shortage:resolve"),
    INVENTORY__SHORTAGE__VIEW(259, "inventory:shortage:view"),
    INVENTORY__STOCK_MOVEMENT__CREATE(260, "inventory:stock_movement:create"),

    // ── Workorder (batch 2) ──────────────────────────────────────────────────
    WORKORDER__PARTS__CONSUME(261, "workorder:parts:consume"),

    // ── Accounting (batch 3) ─────────────────────────────────────────────────
    ACCOUNTING__AP__APPROVE(262, "accounting:ap:approve"),
    ACCOUNTING__AP__REJECT(263, "accounting:ap:reject"),
    ACCOUNTING__COA__DEACTIVATE(264, "accounting:coa:deactivate"),
    ACCOUNTING__JE__REVERSE(265, "accounting:je:reverse"),
    ACCOUNTING__MAPPING__VIEW(266, "accounting:mapping:view"),
    ACCOUNTING__MAPPING__CREATE(267, "accounting:mapping:create"),
    ACCOUNTING__MAPPING__EDIT(268, "accounting:mapping:edit"),
    ACCOUNTING__MAPPING__DEACTIVATE(269, "accounting:mapping:deactivate"),
    ACCOUNTING__POSTING_RULES__VIEW(270, "accounting:posting_rules:view"),
    ACCOUNTING__POSTING_RULES__CREATE(271, "accounting:posting_rules:create"),
    ACCOUNTING__POSTING_RULES__PUBLISH(272, "accounting:posting_rules:publish"),
    ACCOUNTING__POSTING_RULES__ARCHIVE(273, "accounting:posting_rules:archive"),

    // ── Timekeeping (batch 3) ─────────────────────────────────────────────────
    TIMEKEEPING__WORK_SESSION__CREATE(274, "timekeeping:work_session:create"),
    TIMEKEEPING__WORK_SESSION__STOP(275, "timekeeping:work_session:stop"),
    TIMEKEEPING__WORK_SESSION__BREAK_START(276, "timekeeping:work_session:break_start"),
    TIMEKEEPING__WORK_SESSION__BREAK_STOP(277, "timekeeping:work_session:break_stop"),
    TIMEKEEPING__OVERLAP_OVERRIDE(278, "timekeeping:overlap_override"),

    // ── Workorder (batch 3) ──────────────────────────────────────────────────
    WORKORDER__DASHBOARD__VIEW(279, "workorder:dashboard:view"),
    WORKORDER__ESTIMATE__SUBMIT(280, "workorder:estimate:submit"),
    WORKORDER__ESTIMATE__PROMOTE(281, "workorder:estimate:promote"),
    WORKORDER__WORKORDER__ASSIGN_TECHNICIAN(282, "workorder:workorder:assign-technician"),
    WORKORDER__WORKORDER__GENERATE_INVOICE(283, "workorder:workorder:generate_invoice"),
    WORKORDER__START(284, "workorder:start"),
    // ── Accounting (new) ───────────────────────────────────────────────────────
    ACCOUNTING__CREDIT_MEMO__CREATE(285, "accounting:credit-memo:create"),
    ACCOUNTING__CREDIT_MEMO__READ(286, "accounting:credit-memo:read"),
    ACCOUNTING__DEFAULT_MAPPING__CREATE(287, "accounting:default-mapping:create"),
    ACCOUNTING__DEFAULT_MAPPING__DELETE(288, "accounting:default-mapping:delete"),
    ACCOUNTING__DEFAULT_MAPPING__EDIT(289, "accounting:default-mapping:edit"),
    ACCOUNTING__DEFAULT_MAPPING__VIEW(290, "accounting:default-mapping:view"),
    ACCOUNTING__GL_MAPPING__CREATE(291, "accounting:gl-mapping:create"),
    ACCOUNTING__GL_MAPPING__RESOLVE(292, "accounting:gl-mapping:resolve"),
    ACCOUNTING__MAPPING_KEY__CREATE(293, "accounting:mapping-key:create"),
    ACCOUNTING__MAPPING_KEY__DEACTIVATE(294, "accounting:mapping-key:deactivate"),
    ACCOUNTING__MAPPING_KEY__EDIT(295, "accounting:mapping-key:edit"),
    ACCOUNTING__MAPPING_KEY__VIEW(296, "accounting:mapping-key:view"),
    ACCOUNTING__PAYMENT__APPLY(297, "accounting:payment:apply"),
    ACCOUNTING__PAYMENT__REVERSE(298, "accounting:payment:reverse"),
    ACCOUNTING__POSTING_CATEGORY__CREATE(299, "accounting:posting-category:create"),
    ACCOUNTING__POSTING_CATEGORY__DEACTIVATE(300, "accounting:posting-category:deactivate"),
    ACCOUNTING__POSTING_CATEGORY__EDIT(301, "accounting:posting-category:edit"),
    ACCOUNTING__POSTING_CATEGORY__VIEW(302, "accounting:posting-category:view"),
    ACCOUNTING__REPORT__EXPORT(303, "accounting:report:export"),
    ACCOUNTING__TIME__EXPORT(304, "accounting:time:export"),

    // ── Crm (new) ──────────────────────────────────────────────────────────────
    CRM__PERSON__CREATE(305, "crm:person:create"),
    CRM__PERSON__READ(306, "crm:person:read"),
    CRM__RELATIONSHIP__CREATE(307, "crm:relationship:create"),
    CRM__RELATIONSHIP__DELETE(308, "crm:relationship:delete"),
    CRM__RELATIONSHIP__READ(309, "crm:relationship:read"),
    CRM__RELATIONSHIP__UPDATE(310, "crm:relationship:update"),

    // ── Inventory (new) ────────────────────────────────────────────────────────
    INVENTORY__AVAILABILITY__READ(311, "inventory:availability:read"),

    // ── People (new) ───────────────────────────────────────────────────────────
    PEOPLE__AVAILABILITY__VIEW(312, "people:availability:view"),

    // ── Pricing (new) ──────────────────────────────────────────────────────────
    PRICING__OVERRIDE__APPROVE(313, "pricing:override:approve"),
    PRICING__RESTRICTION__MANAGE(314, "pricing:restriction:manage"),
    PRICING__RESTRICTION__OVERRIDE(315, "pricing:restriction:override"),

    // ── Reporting (new) ────────────────────────────────────────────────────────
    REPORTING__VIEW__FINANCIAL_STATEMENTS(316, "reporting:view:financial-statements"),

    // ── Security (new) ─────────────────────────────────────────────────────────
    SECURITY__AUDIT__EXPORT(317, "security:audit:export"),
    // ── People (new) ───────────────────────────────────────────────────────────
    PEOPLE__TIMEADJUSTMENT__APPROVE(318, "people:timeAdjustment:approve"),
    PEOPLE__TIMEADJUSTMENT__CREATE(319, "people:timeAdjustment:create"),
    PEOPLE__TIMEADJUSTMENT__VIEW(320, "people:timeAdjustment:view"),
    PEOPLE__TIMEENTRY__APPROVE(321, "people:timeEntry:approve"),
    PEOPLE__TIMEENTRY__REJECT(322, "people:timeEntry:reject"),
    PEOPLE__TIMEEXCEPTION__ACKNOWLEDGE(323, "people:timeException:acknowledge"),
    PEOPLE__TIMEEXCEPTION__CREATE(324, "people:timeException:create"),
    PEOPLE__TIMEEXCEPTION__RESOLVE(325, "people:timeException:resolve"),
    PEOPLE__TIMEEXCEPTION__VIEW(326, "people:timeException:view"),

    // ── Workorder (new) ────────────────────────────────────────────────────────
    WORKORDER__OPERATIONALCONTEXT__OVERRIDE(327, "workorder:operationalContext:override"),
    // ── People timekeeping approval (new) ────────────────────────────────────────
    PEOPLE__TIMEKEEPING__APPROVE(328, "people:timekeeping:approve"),
    PEOPLE__TIMEKEEPING__REJECT(329, "people:timekeeping:reject"),
    PEOPLE__TIMEKEEPING__VIEW(330, "people:timekeeping:view"),
    // ── Promotion (new) ────────────────────────────────────────────────────────
    PROMOTION__APPLY(331, "Promotion:Apply"),
    PROMOTION__MANAGE(332, "Promotion:Manage"),
    PROMOTION__RECORDREDEMPTION(333, "Promotion:RecordRedemption"),
    PROMOTION__VIEW(334, "Promotion:View"),
    PROMOTION__VIEWREDEMPTION(335, "Promotion:ViewRedemption"),

    // ── Timeentry (new) ────────────────────────────────────────────────────────
    TIMEENTRY__APPROVE(336, "TimeEntry:Approve"),
    TIMEENTRY__REJECT(337, "TimeEntry:Reject"),
    // ── Crm (new) ──────────────────────────────────────────────────────────────
    CRM__PROMOTION_REDEMPTION__RECORD(338, "crm:promotion_redemption:record"),
    CRM__PROMOTION_REDEMPTION__VIEW(339, "crm:promotion_redemption:view"),

    // ── Pricing (new) ──────────────────────────────────────────────────────────
    PRICING__PROMOTION__APPLY(340, "pricing:promotion:apply"),
    PRICING__PROMOTION__MANAGE(341, "pricing:promotion:manage"),
    PRICING__PROMOTION__VIEW(342, "pricing:promotion:view"),

    // ── Workorder (new) ────────────────────────────────────────────────────────
    WORKORDER__TIMEENTRY__APPROVE(343, "workorder:timeEntry:approve"),
    WORKORDER__TIMEENTRY__REJECT(344, "workorder:timeEntry:reject"),
    // ── Workorder (new) ────────────────────────────────────────────────────────
    WORKORDER__LABOR__ADD_ON_BEHALF(345, "workorder:labor:add_on_behalf"),
    // ── Mcp (new) ──────────────────────────────────────────────────────────────
    MCP__TOOL__MANAGE(347, "mcp:tool:manage"),
    MCP__TOOL__VIEW(348, "mcp:tool:view"),
    // ── Inventory (new) ────────────────────────────────────────────────────────
    INVENTORY__LOCATION__SYNC(349, "inventory:location:sync"),
    // ── Workorder (new) ────────────────────────────────────────────────────────
    WORKORDER__EVENTS__REPLAY(350, "workorder:events:replay"),
    // ── People Contact (new, ADR-0044 Phase 3 split — #874) ───────────────────
    PEOPLE_CONTACT__PERSON__VIEW(351, "people-contact:person:view"),
    PEOPLE_CONTACT__PERSON__CREATE(352, "people-contact:person:create"),
    PEOPLE_CONTACT__PERSON__EDIT(353, "people-contact:person:edit"),
    PEOPLE_CONTACT__PERSON__DELETE(354, "people-contact:person:delete"),
    PEOPLE_CONTACT__ROLE__VIEW(355, "people-contact:role:view"),
    PEOPLE_CONTACT__ROLE__ASSIGN(356, "people-contact:role:assign"),
    PEOPLE_CONTACT__ROLE__REVOKE(357, "people-contact:role:revoke"),
    PEOPLE_CONTACT__USER_LINK__VIEW(358, "people-contact:userLink:view"),
    PEOPLE_CONTACT__USER_LINK__WRITE(359, "people-contact:userLink:write"),
    // ── People (new) ───────────────────────────────────────────────────────────
    PEOPLE__COMPLIANCE__VIEW(360, "people:compliance:view"),

    // ── Shop (new) ─────────────────────────────────────────────────────────────
    SHOP__TECHNICIAN__VIEW(361, "shop:technician:view");

    /**
     * Current catalog version. Increment when new permissions are added to a new
     * batch.
     */
    public static final int CATALOG_VERSION = 20;

    private static final Map<String, PermissionCode> BY_CODE =
            Stream.of(values()).collect(Collectors.toUnmodifiableMap(PermissionCode::code, pc -> pc));

    private final int bitIndex;
    private final String code;

    PermissionCode(int bitIndex, String code) {
        this.bitIndex = bitIndex;
        this.code = code;
    }

    /** Returns the permanent bit index assigned to this permission. */
    public int bitIndex() {
        return bitIndex;
    }

    /**
     * Returns the canonical permission code string (e.g.,
     * {@code "accounting:je:view"}).
     */
    public String code() {
        return code;
    }

    /**
     * Looks up a {@code PermissionCode} by its canonical code string.
     *
     * @param code the permission code string
     * @return an {@code Optional} containing the matching constant, or empty if not
     *         found
     */
    public static Optional<PermissionCode> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_CODE.get(code));
    }
}
