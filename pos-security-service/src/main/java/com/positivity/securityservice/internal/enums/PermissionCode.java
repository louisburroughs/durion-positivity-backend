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
 * renumber. See {@code docs/rbac-permission-role-audit-2026-08.md} §3 for the
 * record of which codes were retired and why, and §4 for the retirement
 * convention this annotation implements.
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
    /**
     * No successor. Category is internal validation data with no CRUD
     * endpoints in pos-catalog. Audit doc §3.
     */
    @Deprecated
    CATALOG__CATEGORY__VIEW(17, "catalog:category:view"),
    /** @deprecated see {@link #CATALOG__CATEGORY__VIEW} */
    @Deprecated
    CATALOG__CATEGORY__CREATE(18, "catalog:category:create"),
    /** @deprecated see {@link #CATALOG__CATEGORY__VIEW} */
    @Deprecated
    CATALOG__CATEGORY__EDIT(19, "catalog:category:edit"),
    /** @deprecated see {@link #CATALOG__CATEGORY__VIEW} */
    @Deprecated
    CATALOG__CATEGORY__DELETE(20, "catalog:category:delete"),
    CATALOG__SERVICE_TYPE__VIEW(21, "catalog:service_type:view"),
    CATALOG__SERVICE_TYPE__CREATE(22, "catalog:service_type:create"),
    CATALOG__SERVICE_TYPE__EDIT(23, "catalog:service_type:edit"),
    /**
     * No successor. Variants/tread designs are Kafka-written, not mutated
     * through an API. Audit doc §3.
     */
    @Deprecated
    CATALOG__VARIANT__VIEW(24, "catalog:variant:view"),
    /** @deprecated see {@link #CATALOG__VARIANT__VIEW} */
    @Deprecated
    CATALOG__VARIANT__CREATE(25, "catalog:variant:create"),
    /** @deprecated see {@link #CATALOG__VARIANT__VIEW} */
    @Deprecated
    CATALOG__VARIANT__EDIT(26, "catalog:variant:edit"),

    // ── CRM ──────────────────────────────────────────────────────────────────
    CRM__PARTY__VIEW(27, "crm:party:view"),
    CRM__PARTY__SEARCH(28, "crm:party:search"),
    CRM__PARTY__CREATE(29, "crm:party:create"),
    CRM__PARTY__EDIT(30, "crm:party:edit"),
    CRM__PARTY__DEACTIVATE(31, "crm:party:deactivate"),
    CRM__PARTY__MERGE(32, "crm:party:merge"),
    CRM__CONTACT__VIEW(33, "crm:contact:view"),
    /**
     * Superseded by {@link #PEOPLE_CONTACT__PERSON__EDIT}. Contact points
     * live in pos-people-contact, not pos-customer. Audit doc §3.
     */
    @Deprecated
    CRM__CONTACT__CREATE(34, "crm:contact:create"),
    /** @deprecated see {@link #CRM__CONTACT__CREATE} */
    @Deprecated
    CRM__CONTACT__EDIT(35, "crm:contact:edit"),
    /** @deprecated see {@link #CRM__CONTACT__CREATE} */
    @Deprecated
    CRM__CONTACT__DELETE(36, "crm:contact:delete"),
    /**
     * Superseded by {@link #CRM__CONTACT__VIEW} — roles are inline on the
     * contact response; no separate role-view endpoint exists. Audit doc §3.
     */
    @Deprecated
    CRM__CONTACT_ROLE__VIEW(37, "crm:contact_role:view"),
    CRM__CONTACT_ROLE__ASSIGN(38, "crm:contact_role:assign"),
    /**
     * Superseded by {@link #CRM__CONTACT_ROLE__ASSIGN} — revocation is a
     * full-set replace via assign, not a separate revoke endpoint. Audit doc §3.
     */
    @Deprecated
    CRM__CONTACT_ROLE__REVOKE(39, "crm:contact_role:revoke"),
    CRM__CONTACT_PREFERENCE__VIEW(40, "crm:contact_preference:view"),
    CRM__CONTACT_PREFERENCE__EDIT(41, "crm:contact_preference:edit"),
    CRM__VEHICLE__VIEW(42, "crm:vehicle:view"),
    /**
     * Superseded by {@link #VEHICLE_INVENTORY__SEARCH__VIEW}
     * ({@code vehicle-inventory:search:view}, pos-vehicle-inventory
     * VehicleSearchController). ADR-0044 §6; audit doc §3.
     */
    @Deprecated
    CRM__VEHICLE__SEARCH(43, "crm:vehicle:search"),
    CRM__VEHICLE__CREATE(44, "crm:vehicle:create"),
    /**
     * Superseded by {@link #VEHICLE_INVENTORY__REGISTRY__UPDATE}
     * ({@code vehicle-inventory:registry:update}). ADR-0044 §6;
     * {@code CrmPermissionRegistry.java} marks this retired.
     */
    @Deprecated
    CRM__VEHICLE__EDIT(45, "crm:vehicle:edit"),
    /**
     * Superseded by {@link #VEHICLE_INVENTORY__REGISTRY__DELETE}
     * ({@code vehicle-inventory:registry:delete}). ADR-0044 §6;
     * {@code CrmPermissionRegistry.java} marks this retired.
     */
    @Deprecated
    CRM__VEHICLE__DEACTIVATE(46, "crm:vehicle:deactivate"),
    /**
     * No successor. This family is event-driven only
     * ({@code VehicleEventsListener}) — no API exists for it, by design.
     * ADR-0044 §6; audit doc §3.
     */
    @Deprecated
    CRM__VEHICLE_PARTY_ASSOCIATION__CREATE(47, "crm:vehicle_party_association:create"),
    /** @deprecated see {@link #CRM__VEHICLE_PARTY_ASSOCIATION__CREATE} */
    @Deprecated
    CRM__VEHICLE_PARTY_ASSOCIATION__VIEW(48, "crm:vehicle_party_association:view"),
    /** @deprecated see {@link #CRM__VEHICLE_PARTY_ASSOCIATION__CREATE} */
    @Deprecated
    CRM__VEHICLE_PARTY_ASSOCIATION__EDIT(49, "crm:vehicle_party_association:edit"),
    /**
     * Superseded by {@link #VEHICLE_INVENTORY__PREFERENCES__MANAGE}
     * ({@code vehicle-inventory:preferences:manage}). ADR-0044 §6.
     */
    @Deprecated
    CRM__VEHICLE_PREFERENCE__VIEW(50, "crm:vehicle_preference:view"),
    /** @deprecated see {@link #CRM__VEHICLE_PREFERENCE__VIEW} */
    @Deprecated
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
    /**
     * Superseded by {@link #INVENTORY__AVAILABILITY__READ}
     * ({@code inventory:availability:read}). ADR-0057, #1497, #1499;
     * audit doc §3.
     */
    @Deprecated
    INVENTORY__ON_HAND__SEARCH(65, "inventory:on_hand:search"),
    INVENTORY__RECEIVING__CREATE(66, "inventory:receiving:create"),
    INVENTORY__RECEIVING__VIEW(67, "inventory:receiving:view"),
    INVENTORY__RECEIVING__COMPLETE(68, "inventory:receiving:complete"),
    INVENTORY__ISSUE__PARTS(69, "inventory:issue:parts"),
    INVENTORY__OVERRIDE__PART_MATCH(70, "inventory:override:part-match"),
    /**
     * Superseded by {@link #ORDER__PURCHASE_ORDER__CREATE}
     * ({@code order:purchase_order:create}). Seed header; tracked on #1438.
     */
    @Deprecated
    INVENTORY__PURCHASE_ORDER__CREATE(71, "inventory:purchase_order:create"),
    /**
     * Superseded by {@link #ORDER__PURCHASE_ORDER__VIEW}
     * ({@code order:purchase_order:view}). Seed header; tracked on #1438.
     */
    @Deprecated
    INVENTORY__PURCHASE_ORDER__VIEW(72, "inventory:purchase_order:view"),
    /**
     * Superseded by {@link #ORDER__PURCHASE_ORDER__APPROVE}
     * ({@code order:purchase_order:approve}). Seed header; tracked on #1438.
     */
    @Deprecated
    INVENTORY__PURCHASE_ORDER__APPROVE(73, "inventory:purchase_order:approve"),
    /**
     * No 1:1 successor, unlike its create/view/approve siblings: receiving
     * stayed in pos-inventory as {@link #INVENTORY__GOODS_RECEIPT__CREATE}
     * and {@link #INVENTORY__RECEIVING__COMPLETE}, not the
     * {@code order:purchase_order:*} family. Audit doc §3.
     */
    @Deprecated
    INVENTORY__PURCHASE_ORDER__RECEIVE(74, "inventory:purchase_order:receive"),
    INVENTORY__ASN__CREATE(75, "inventory:asn:create"),
    INVENTORY__ASN__VIEW(76, "inventory:asn:view"),
    INVENTORY__GOODS_RECEIPT__CREATE(77, "inventory:goods_receipt:create"),
    INVENTORY__GOODS_RECEIPT__VIEW(78, "inventory:goods_receipt:view"),
    INVENTORY__GOODS_RECEIPT__OVERRIDE(79, "inventory:goods_receipt:override"),
    INVENTORY__ALLOCATIONS__REALLOCATE(80, "inventory:allocations:reallocate"),
    /**
     * Superseded by {@link #INVENTORY__SHORTAGE__RESOLVE}
     * ({@code inventory:shortage:resolve}) — singular rename; only the
     * singular family is enforced/granted. Audit doc §3.
     */
    @Deprecated
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
    /**
     * Superseded by {@link #ORDER__ORDER__VIEW} — lines are embedded in the
     * order response; there is no separate line-view endpoint. Audit doc §3.
     */
    @Deprecated
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
    /**
     * Superseded by {@link #SECURITY__ROLE__VIEW} — no people-module role
     * endpoints exist; role management lives in pos-security-service.
     * Audit doc §3.
     */
    @Deprecated
    PEOPLE__ROLE__VIEW(120, "people:role:view"),
    /**
     * Superseded by {@link #SECURITY__ROLE__ASSIGN}. Audit doc §3.
     */
    @Deprecated
    PEOPLE__ROLE__ASSIGN(121, "people:role:assign"),
    /**
     * No successor — {@code security:role} has no revoke action. Audit doc §3.
     */
    @Deprecated
    PEOPLE__ROLE__REVOKE(122, "people:role:revoke"),
    PEOPLE__SKILL__VIEW(123, "people:skill:view"),
    PEOPLE__SKILL__ASSIGN(124, "people:skill:assign"),
    PEOPLE__SKILL__EDIT(125, "people:skill:edit"),

    // ── Pricing ──────────────────────────────────────────────────────────────
    /**
     * No successor. No PriceBook resource exists in pos-price. Audit doc §3.
     */
    @Deprecated
    PRICING__PRICE_BOOK__VIEW(126, "pricing:price_book:view"),
    /** @deprecated see {@link #PRICING__PRICE_BOOK__VIEW} */
    @Deprecated
    PRICING__PRICE_BOOK__CREATE(127, "pricing:price_book:create"),
    /** @deprecated see {@link #PRICING__PRICE_BOOK__VIEW} */
    @Deprecated
    PRICING__PRICE_BOOK__EDIT(128, "pricing:price_book:edit"),
    /** @deprecated see {@link #PRICING__PRICE_BOOK__VIEW} */
    @Deprecated
    PRICING__PRICE_BOOK__DELETE(129, "pricing:price_book:delete"),
    PRICING__NORMALIZATION__VIEW(130, "pricing:normalization:view"),
    PRICING__NORMALIZATION__EDIT(131, "pricing:normalization:edit"),
    PRICING__RESTRICTIONS__VIEW(132, "pricing:restrictions:view"),
    /**
     * Superseded by {@link #PRICING__RESTRICTION__MANAGE}
     * ({@code pricing:restriction:manage}, singular resource name).
     * Audit doc §3.
     */
    @Deprecated
    PRICING__RESTRICTIONS__EDIT(133, "pricing:restrictions:edit"),
    PRICING__RULE__VIEW(134, "pricing:rule:view"),
    /**
     * Superseded by {@link #PRICING__RESTRICTION__MANAGE}
     * ({@code pricing:restriction:manage}, singular resource name).
     * Audit doc §3.
     */
    @Deprecated
    PRICING__RULE__CREATE(135, "pricing:rule:create"),
    /**
     * No successor — no edit endpoint exists for pricing rules at all.
     * Audit doc §3.
     */
    @Deprecated
    PRICING__RULE__EDIT(136, "pricing:rule:edit"),
    /** @deprecated see {@link #PRICING__RULE__CREATE} */
    @Deprecated
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
    /**
     * Superseded by {@link #LOCATION__READ} ({@code location:read},
     * pos-location). pos-shop-manager's contract enforces none of the
     * {@code shop:location:*}/{@code shop:bay:*} codes. Audit doc §3.
     */
    @Deprecated
    SHOP__LOCATION__VIEW(149, "shop:location:view"),
    /**
     * Superseded by {@link #LOCATION__WRITE} ({@code location:write},
     * pos-location). Audit doc §3.
     */
    @Deprecated
    SHOP__LOCATION__CREATE(150, "shop:location:create"),
    /** @deprecated see {@link #SHOP__LOCATION__CREATE} */
    @Deprecated
    SHOP__LOCATION__EDIT(151, "shop:location:edit"),
    /** @deprecated see {@link #SHOP__LOCATION__CREATE} */
    @Deprecated
    SHOP__LOCATION__DEACTIVATE(152, "shop:location:deactivate"),
    /**
     * Superseded by {@link #LOCATION__BAY__READ} ({@code location:bay:read},
     * pos-location). Audit doc §3.
     */
    @Deprecated
    SHOP__BAY__VIEW(153, "shop:bay:view"),
    /**
     * Superseded by {@link #LOCATION__BAY__MANAGE}
     * ({@code location:bay:manage}, pos-location). Audit doc §3.
     */
    @Deprecated
    SHOP__BAY__CREATE(154, "shop:bay:create"),
    /** @deprecated see {@link #SHOP__BAY__CREATE} */
    @Deprecated
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
    /**
     * Superseded by {@link #WORKORDER__WORKORDER__GENERATE_INVOICE}
     * ({@code workorder:workorder:generate_invoice}). pos-workorder
     * contract; audit doc §3.
     */
    @Deprecated
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
    /**
     * No successor — supplier cost is Kafka-ingest only. The read side
     * {@link #CATALOG__SUPPLIER_COST__READ} stays enforced and stays
     * granted. Audit doc §3.
     */
    @Deprecated
    CATALOG__SUPPLIER_COST__WRITE(228, "catalog:supplier_cost:write"),
    CATALOG__MSRP__READ(229, "catalog:msrp:read"),
    CATALOG__MSRP__WRITE(230, "catalog:msrp:write"),
    CATALOG__PRICE_BOOK__READ(231, "catalog:price_book:read"),
    CATALOG__PRICE_BOOK__WRITE(232, "catalog:price_book:write"),

    // ── People (batch 3) ─────────────────────────────────────────────────────
    /**
     * Superseded by {@link #PEOPLE__EMPLOYEE__VIEW} — the pos-people
     * manifest registers only {@code employee}, not {@code person}.
     * Audit doc §3.
     */
    @Deprecated
    PEOPLE__PERSON__VIEW(233, "people:person:view"),
    /** @deprecated see {@link #PEOPLE__EMPLOYEE__CREATE} */
    @Deprecated
    PEOPLE__PERSON__CREATE(234, "people:person:create"),
    /** @deprecated see {@link #PEOPLE__EMPLOYEE__EDIT} */
    @Deprecated
    PEOPLE__PERSON__EDIT(235, "people:person:edit"),
    /**
     * INFERRED successor {@link #PEOPLE__EMPLOYEE__DEACTIVATE} — employee
     * has no delete action, only deactivate; no supersededBy mapping is
     * recorded for this one in the manifest layer, since deactivate is not
     * a literal 1:1 match for delete. Audit doc §3.
     */
    @Deprecated
    PEOPLE__PERSON__DELETE(236, "people:person:delete"),
    /**
     * Superseded by {@code people-contact:userLink:view} (bit 358,
     * {@link #PEOPLE_CONTACT__USER_LINK__VIEW}) — module split.
     * Audit doc §3.
     */
    @Deprecated
    PEOPLE__USER_LINK__VIEW(237, "people:userLink:view"),
    /**
     * Superseded by {@code people-contact:userLink:write} (bit 359,
     * {@link #PEOPLE_CONTACT__USER_LINK__WRITE}) — module split.
     * Audit doc §3.
     */
    @Deprecated
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
    /**
     * Superseded by {@link #ACCOUNTING__AP__PAY} — the only AP action
     * pos-accounting actually enforces. Audit doc §3.
     */
    @Deprecated
    ACCOUNTING__AP__APPROVE(262, "accounting:ap:approve"),
    /** @deprecated see {@link #ACCOUNTING__AP__APPROVE} */
    @Deprecated
    ACCOUNTING__AP__REJECT(263, "accounting:ap:reject"),
    ACCOUNTING__COA__DEACTIVATE(264, "accounting:coa:deactivate"),
    ACCOUNTING__JE__REVERSE(265, "accounting:je:reverse"),
    /**
     * No 1:1 successor — the mapping surface split into a family:
     * {@link #ACCOUNTING__GL_MAPPING__CREATE gl-mapping},
     * {@link #ACCOUNTING__MAPPING_KEY__VIEW mapping-key}, and
     * {@link #ACCOUNTING__DEFAULT_MAPPING__VIEW default-mapping}. Audit doc §3.
     */
    @Deprecated
    ACCOUNTING__MAPPING__VIEW(266, "accounting:mapping:view"),
    /** @deprecated see {@link #ACCOUNTING__MAPPING__VIEW} */
    @Deprecated
    ACCOUNTING__MAPPING__CREATE(267, "accounting:mapping:create"),
    /** @deprecated see {@link #ACCOUNTING__MAPPING__VIEW} */
    @Deprecated
    ACCOUNTING__MAPPING__EDIT(268, "accounting:mapping:edit"),
    /** @deprecated see {@link #ACCOUNTING__MAPPING__VIEW} */
    @Deprecated
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
    /**
     * Split-brain with {@link #WORKORDER__WORKORDER__START} (bit 180):
     * both are enforced in different places (the start endpoint checks this
     * one; the detail-response capability flag checks the other).
     * {@code workorder:workorder:start} wins per the
     * {@code domain:resource:action} convention. Audit doc §2 fix 1, §3.
     */
    @Deprecated
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
    /**
     * Superseded by {@code people:timeEntry:approve}. pos-workorder's time_entry table
     * had no writer, so the endpoint this guarded could only answer 404; employee time
     * entries live in pos-people. Audit doc §3, #1564.
     */
    @Deprecated
    WORKORDER__TIMEENTRY__APPROVE(343, "workorder:timeEntry:approve"),
    /** @deprecated see {@link #WORKORDER__TIMEENTRY__APPROVE}; superseded by {@code people:timeEntry:reject} */
    @Deprecated
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
    SHOP__TECHNICIAN__VIEW(361, "shop:technician:view"),

    // ── Warranty (new) ─────────────────────────────────────────────────────────
    WARRANTY__PROVIDER__VIEW(362, "warranty:provider:view"),
    WARRANTY__PROVIDER__MANAGE(363, "warranty:provider:manage"),
    WARRANTY__POLICY__VIEW(364, "warranty:policy:view"),
    WARRANTY__POLICY__MANAGE(365, "warranty:policy:manage"),
    WARRANTY__REGISTRATION__VIEW(366, "warranty:registration:view"),
    WARRANTY__REGISTRATION__MANAGE(367, "warranty:registration:manage"),
    WARRANTY__CLAIM__VIEW(368, "warranty:claim:view"),
    WARRANTY__CLAIM__CREATE(369, "warranty:claim:create"),
    WARRANTY__CLAIM__SUBMIT(370, "warranty:claim:submit"),
    WARRANTY__CLAIM__DECIDE(371, "warranty:claim:decide"),
    WARRANTY__CLAIM__SETTLE(372, "warranty:claim:settle"),
    WARRANTY__CLAIM__CANCEL(373, "warranty:claim:cancel"),
    WARRANTY__CLAIM__CLOSE(374, "warranty:claim:close"),
    WARRANTY__REIMBURSEMENT__VIEW(375, "warranty:reimbursement:view"),
    WARRANTY__REIMBURSEMENT__MANAGE(376, "warranty:reimbursement:manage"),
    WARRANTY__PART_RETURN__VIEW(377, "warranty:part-return:view"),
    WARRANTY__PART_RETURN__MANAGE(378, "warranty:part-return:manage"),
    // ── Accounting (new) ───────────────────────────────────────────────────────
    ACCOUNTING__PERIOD__CLOSE(379, "accounting:period:close"),
    ACCOUNTING__PERIOD__REOPEN(380, "accounting:period:reopen"),
    ACCOUNTING__PERIOD__VIEW(381, "accounting:period:view"),
    ACCOUNTING__PERIOD__HARD_LOCK(382, "accounting:period:hard_lock"),
    ACCOUNTING__PERIOD__OVERRIDE(383, "accounting:period:override"),
    // ── Accounting (new) ───────────────────────────────────────────────────────
    ACCOUNTING__RECONCILIATION__ADJUST(384, "accounting:reconciliation:adjust"),
    ACCOUNTING__RECONCILIATION__VIEW(385, "accounting:reconciliation:view"),
    // ── Tax (new) ──────────────────────────────────────────────────────────────
    TAX__EXEMPTION__MANAGE(386, "tax:exemption:manage"),
    TAX__EXEMPTION__VIEW(387, "tax:exemption:view"),
    // ── Tax provider lifecycle (new) ─────────────────────────────────────────────
    TAX__COMMIT(388, "tax:commit"),
    // ── Accounting customer-credit lifecycle (issue #992) ────────────────────────
    ACCOUNTING__CUSTOMER_CREDIT__VIEW(389, "accounting:customer-credit:view"),
    ACCOUNTING__CUSTOMER_CREDIT__APPLY(390, "accounting:customer-credit:apply"),
    ACCOUNTING__CUSTOMER_CREDIT__REFUND(391, "accounting:customer-credit:refund"),
    // ── Accounting (new) ───────────────────────────────────────────────────────
    ACCOUNTING__TAX_SNAPSHOT__FREEZE(392, "accounting:tax-snapshot:freeze"),
    // ── Accounting (new) ───────────────────────────────────────────────────────
    ACCOUNTING__CREDIT_MEMO__VOID(393, "accounting:credit-memo:void"),
    // ── Inventory (new) ────────────────────────────────────────────────────────
    INVENTORY__SCRAP__APPROVE(394, "inventory:scrap:approve"),
    INVENTORY__SCRAP__CREATE(395, "inventory:scrap:create"),
    INVENTORY__SCRAP__VIEW(396, "inventory:scrap:view"),
    // ── Inventory (new) ────────────────────────────────────────────────────────
    INVENTORY__TRANSFER__CREATE(397, "inventory:transfer:create"),
    INVENTORY__TRANSFER__DISPATCH(398, "inventory:transfer:dispatch"),
    INVENTORY__TRANSFER__RECEIVE(399, "inventory:transfer:receive"),
    INVENTORY__TRANSFER__SHORT_CLOSE(400, "inventory:transfer:short_close"),
    INVENTORY__TRANSFER__VIEW(401, "inventory:transfer:view"),
    ORDER__ORDER__DISCOUNT(402, "order:order:discount"),
    ORDER__ORDER__QUOTE(403, "order:order:quote"),
    ORDER__ORDER__CHECKOUT(404, "order:order:checkout"),
    ORDER__ORDER__VOID(405, "order:order:void"),
    ORDER__ORDER__CHARGE_ON_ACCOUNT(406, "order:order:charge_on_account"),
    // ── Order register sessions & cash management (odoo-parity G1/G2) ─────────────
    ORDER__SESSION__OPEN(407, "order:session:open"),
    ORDER__SESSION__VIEW(408, "order:session:view"),
    ORDER__SESSION__CASH_MOVEMENT(409, "order:session:cash_movement"),
    ORDER__SESSION__CLOSE(410, "order:session:close"),
    ORDER__SESSION__APPROVE_VARIANCE(411, "order:session:approve_variance"),
    // ── Order returns & refunds (odoo-parity F1/F2) ──────────────────────────────
    ORDER__RETURN__CREATE(412, "order:return:create"),
    ORDER__RETURN__APPROVE(413, "order:return:approve"),
    ORDER__RETURN__VIEW(414, "order:return:view"),
    // ── Inventory (new) ────────────────────────────────────────────────────────
    INVENTORY__LOT__MANAGE(415, "inventory:lot:manage"),
    // ── Inventory (new) ────────────────────────────────────────────────────────
    INVENTORY__VALUATION__VIEW(416, "inventory:valuation:view"),
    // ── Inventory (new) ────────────────────────────────────────────────────────
    INVENTORY__VALUATION__ADJUST(417, "inventory:valuation:adjust"),
    // ── Crm (new) ──────────────────────────────────────────────────────────────
    CRM__CONSENT__MANAGE(418, "crm:consent:manage"),
    CRM__CONSENT__VIEW(419, "crm:consent:view"),
    CRM__INTERACTION__VIEW(420, "crm:interaction:view"),
    CRM__SEGMENT__MANAGE(421, "crm:segment:manage"),
    CRM__SEGMENT__RESOLVE(422, "crm:segment:resolve"),
    CRM__SEGMENT__VIEW(423, "crm:segment:view"),
    CRM__SUPPRESSION__MANAGE(424, "crm:suppression:manage"),
    CRM__SUPPRESSION__VIEW(425, "crm:suppression:view"),
    CRM__TAG__ASSIGN(426, "crm:tag:assign"),
    CRM__TAG__MANAGE(427, "crm:tag:manage"),
    CRM__TAG__VIEW(428, "crm:tag:view"),

    // ── Marketing (new) ────────────────────────────────────────────────────────
    MARKETING__CAMPAIGN__CREATE(429, "marketing:campaign:create"),
    MARKETING__CAMPAIGN__EDIT(430, "marketing:campaign:edit"),
    MARKETING__CAMPAIGN__MANAGE(431, "marketing:campaign:manage"),
    MARKETING__CAMPAIGN__SCHEDULE(432, "marketing:campaign:schedule"),
    MARKETING__CAMPAIGN__SEND(433, "marketing:campaign:send"),
    MARKETING__CAMPAIGN__VIEW(434, "marketing:campaign:view"),
    MARKETING__STATS__VIEW(435, "marketing:stats:view"),
    MARKETING__TEMPLATE__MANAGE(436, "marketing:template:manage"),
    MARKETING__TEMPLATE__VIEW(437, "marketing:template:view"),
    // ── Crm (new) ──────────────────────────────────────────────────────────────
    CRM__FOLLOWUP__MANAGE(438, "crm:followup:manage"),
    CRM__FOLLOWUP__VIEW(439, "crm:followup:view"),
    CRM__INQUIRY__MANAGE(440, "crm:inquiry:manage"),
    CRM__INQUIRY__VIEW(441, "crm:inquiry:view"),
    // ── Crm (new) ──────────────────────────────────────────────────────────────
    CRM__INTERACTION__MANAGE(442, "crm:interaction:manage"),
    // ── People Contact (new) ───────────────────────────────────────────────────
    PEOPLE_CONTACT__ORGANIZATION__EDIT(443, "people-contact:organization:edit"),
    PEOPLE_CONTACT__ORGANIZATION__VIEW(444, "people-contact:organization:view"),

    // ── Supplier (new) ─────────────────────────────────────────────────────────
    SUPPLIER__AUDIT__READ(445, "supplier:audit:read"),
    SUPPLIER__PROFILE__READ(446, "supplier:profile:read"),
    SUPPLIER__PROFILE__WRITE(447, "supplier:profile:write"),

    // ── Supplier price catalog (CAP-318 #1224) ─────────────────────────────────
    SUPPLIER__PRICECATALOG__READ(448, "supplier:pricecatalog:read"),
    SUPPLIER__PRICECATALOG__IMPORT(449, "supplier:pricecatalog:import"),
    // ── Inventory (new) ────────────────────────────────────────────────────────
    INVENTORY__SUPPLIER_STOCK_HINT__VIEW(450, "inventory:supplier_stock_hint:view"),
    // ── Supplier (new) ─────────────────────────────────────────────────────────
    SUPPLIER__TRANSMISSION__READ(451, "supplier:transmission:read"),
    SUPPLIER__TRANSMISSION__RESOLVE(452, "supplier:transmission:resolve"),
    SUPPLIER__STOCK__INQUIRE(453, "supplier:stock:inquire"),
    // ── Inventory (new) ────────────────────────────────────────────────────────
    INVENTORY__REPLENISHMENT__MANAGE(454, "inventory:replenishment:manage"),
    // ── Catalog (new) ──────────────────────────────────────────────────────────
    CATALOG__ITEM_COST__UPDATE(455, "catalog:item_cost:update"),
    // ── Order (new) ────────────────────────────────────────────────────────────
    ORDER__PURCHASE_ORDER__APPROVE(456, "order:purchase_order:approve"),
    ORDER__PURCHASE_ORDER__CREATE(457, "order:purchase_order:create"),
    ORDER__PURCHASE_ORDER__VIEW(458, "order:purchase_order:view"),
    // ── Order (new) ────────────────────────────────────────────────────────────
    ORDER__PURCHASE_ORDER__TRANSMIT(459, "order:purchase_order:transmit"),
    // ── Order (new) ────────────────────────────────────────────────────────────
    ORDER__PURCHASE_ORDER__AVAILABILITY_VIEW(460, "order:purchase_order:availability_view"),
    // ── Supplier (new) ─────────────────────────────────────────────────────────
    SUPPLIER__INVOICE__FETCH(461, "supplier:invoice:fetch"),
    // ── Supplier (new) ─────────────────────────────────────────────────────────
    SUPPLIER__WORKORDERAUTH__REQUEST(462, "supplier:workorderauth:request"),
    // ── Supplier (new) ─────────────────────────────────────────────────────────
    SUPPLIER__WORKORDERAUTH__REVIEW(463, "supplier:workorderauth:review"),
    // ── Image (new) ────────────────────────────────────────────────────────────
    IMAGE__IMAGE__STORE(464, "image:image:store"),

    // ── Supplier (new) ─────────────────────────────────────────────────────────
    SUPPLIER__MKTCAT__IMPORT(465, "supplier:mktcat:import"),
    // ── Workorder (new) ────────────────────────────────────────────────────────
    WORKORDER__FLEET_AUTH__REQUEST(466, "workorder:fleet_auth:request"),
    WORKORDER__FLEET_AUTH__RESOLVE(467, "workorder:fleet_auth:resolve"),
    // ── Inventory (new) ────────────────────────────────────────────────────────
    INVENTORY__ADJUSTMENT__OVERRIDE(468, "inventory:adjustment:override"),
    // ── Inventory (new) ────────────────────────────────────────────────────────
    INVENTORY__CYCLE_COUNT_TOLERANCE__MANAGE(469, "inventory:cycle_count_tolerance:manage"),
    // ── Inventory (new) ────────────────────────────────────────────────────────
    // Cross-location availability: the per-location breakdown, split from the
    // scope-limited read at bit 311 (ADR-0057, #1494).
    INVENTORY__AVAILABILITY__SEARCH(470, "inventory:availability:search"),
    WORKORDER__FINANCIALS__VIEW(471, "workorder:financials:view"),
    // ── Catalog (new) ──────────────────────────────────────────────────────────
    CATALOG__GUARDRAIL_POLICY__WRITE(472, "catalog:guardrail_policy:write"),
    CATALOG__LOCATION_PRICE_OVERRIDE__READ(473, "catalog:location_price_override:read"),
    CATALOG__LOCATION_PRICE_OVERRIDE__WRITE(474, "catalog:location_price_override:write"),
    CATALOG__NON_INVENTORY__VIEW(475, "catalog:non_inventory:view"),
    CATALOG__SUBSTITUTION_GROUP__VIEW(476, "catalog:substitution_group:view"),
    CATALOG__SUBSTITUTION_GROUP__EDIT(477, "catalog:substitution_group:edit"),
    CATALOG__CATALOG_GROUPING__VIEW(478, "catalog:catalog_grouping:view"),
    CATALOG__CATALOG_GROUPING__EDIT(479, "catalog:catalog_grouping:edit"),
    CATALOG__CATALOG_GROUPING__DELETE(480, "catalog:catalog_grouping:delete"),
    CATALOG__UOM_CONVERSION__VIEW(481, "catalog:uom_conversion:view"),
    CATALOG__UOM_CONVERSION__EDIT(482, "catalog:uom_conversion:edit"),
    CATALOG__PRODUCT_UOM__VIEW(483, "catalog:product_uom:view"),
    CATALOG__PRODUCT_UOM__EDIT(484, "catalog:product_uom:edit"),
    CATALOG__ITEM_COST__READ(485, "catalog:item_cost:read"),
    CATALOG__TREAD_DESIGN__VIEW(486, "catalog:tread_design:view"),
    CATALOG__FACT__REPLAY(487, "catalog:fact:replay"),
    // ── People (new) ───────────────────────────────────────────────────────────
    PEOPLE__TIMEPERIOD__CREATE(488, "people:timePeriod:create"),
    PEOPLE__TIMEPERIOD__TRANSITION(489, "people:timePeriod:transition"),
    // ── Tax (new) ──────────────────────────────────────────────────────────────
    TAX__RATES__VIEW(490, "tax:rates:view"),
    // ── Inventory (new) ────────────────────────────────────────────────────────
    INVENTORY__PUTAWAY_RULE__MANAGE(491, "inventory:putaway_rule:manage"),
    INVENTORY__PUTAWAY_RULE__VIEW(492, "inventory:putaway_rule:view"),
    // ── People (new) ───────────────────────────────────────────────────────────
    PEOPLE__TIMEENTRY__VIEW(493, "people:timeEntry:view"),
    // ── Invoice (new) ──────────────────────────────────────────────────────────
    INVOICE__INVOICE__VIEW(494, "invoice:invoice:view"),
    // ── Invoice (new) ──────────────────────────────────────────────────────────
    INVOICE__ANALYTICS__VIEW(495, "invoice:analytics:view"),
    // ── Accounting (new) ───────────────────────────────────────────────────────
    ACCOUNTING__ANALYTICS__VIEW(496, "accounting:analytics:view"),

    // ── Workorder (new) ────────────────────────────────────────────────────────
    WORKORDER__ANALYTICS__VIEW(497, "workorder:analytics:view");

    /**
     * Current catalog version. Increment when new permissions are added to a new
     * batch.
     */
    public static final int CATALOG_VERSION = 68;

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
