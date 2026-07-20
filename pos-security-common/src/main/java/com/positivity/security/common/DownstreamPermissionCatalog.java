package com.positivity.security.common;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Downstream mirror of {@code GatewayPermissionCatalog} used by
 * {@link GatewayAuthoritiesFilter} to decode the compact {@code X-Perm-Bits}
 * header injected by the API gateway.
 *
 * <p>This array is a structural copy of
 * {@code pos-api-gateway/.../GatewayPermissionCatalog.AUTHORITY_BY_BIT}.
 * Both files are kept in sync by {@code scripts/generate-permissions.py --sync}.
 * Bit indices are permanent — never reorder or remove entries.
 */
public final class DownstreamPermissionCatalog {

    private DownstreamPermissionCatalog() {}

    /**
     * Must match {@code GatewayPermissionCatalog.CATALOG_VERSION} and
     * {@code PermissionCode.CATALOG_VERSION}.
     * Updated automatically by {@code scripts/generate-permissions.py --sync}.
     */
    public static final int CATALOG_VERSION = 24;

    /**
     * Index-to-authority mapping. Entry at position N is the {@code PERM_*}-prefixed
     * authority string for bit N in the {@code X-Perm-Bits} header.
     */
    // Package-private: use authorityForBit() and authoritiesFromBitSet() as the public API.
    static final String[] AUTHORITY_BY_BIT = {
        "PERM_accounting:je:view",
        "PERM_accounting:je:create",
        "PERM_accounting:je:post",
        "PERM_accounting:ap:view",
        "PERM_accounting:ap:pay",
        "PERM_accounting:coa:view",
        "PERM_accounting:coa:create",
        "PERM_accounting:coa:edit",
        "PERM_accounting:events:view",
        "PERM_accounting:events:submit",
        "PERM_accounting:events:retry",
        "PERM_catalog:product:view",
        "PERM_catalog:product:create",
        "PERM_catalog:product:edit",
        "PERM_catalog:product:delete",
        "PERM_product:lifecycle:update",
        "PERM_product:lifecycle:override_discontinued",
        "PERM_catalog:category:view",
        "PERM_catalog:category:create",
        "PERM_catalog:category:edit",
        "PERM_catalog:category:delete",
        "PERM_catalog:service_type:view",
        "PERM_catalog:service_type:create",
        "PERM_catalog:service_type:edit",
        "PERM_catalog:variant:view",
        "PERM_catalog:variant:create",
        "PERM_catalog:variant:edit",
        "PERM_crm:party:view",
        "PERM_crm:party:search",
        "PERM_crm:party:create",
        "PERM_crm:party:edit",
        "PERM_crm:party:deactivate",
        "PERM_crm:party:merge",
        "PERM_crm:contact:view",
        "PERM_crm:contact:create",
        "PERM_crm:contact:edit",
        "PERM_crm:contact:delete",
        "PERM_crm:contact_role:view",
        "PERM_crm:contact_role:assign",
        "PERM_crm:contact_role:revoke",
        "PERM_crm:contact_preference:view",
        "PERM_crm:contact_preference:edit",
        "PERM_crm:vehicle:view",
        "PERM_crm:vehicle:search",
        "PERM_crm:vehicle:create",
        "PERM_crm:vehicle:edit",
        "PERM_crm:vehicle:deactivate",
        "PERM_crm:vehicle_party_association:create",
        "PERM_crm:vehicle_party_association:view",
        "PERM_crm:vehicle_party_association:edit",
        "PERM_crm:vehicle_preference:view",
        "PERM_crm:vehicle_preference:edit",
        "PERM_crm:processing_log:view",
        "PERM_crm:suspense:view",
        "PERM_crm:integration:audit",
        "PERM_documents:render",
        "PERM_inventory:adjustment:create",
        "PERM_inventory:adjustment:approve",
        "PERM_inventory:adjustment:view",
        "PERM_inventory:putaway:override_location_compatibility",
        "PERM_inventory:putaway:override_location_capacity",
        "PERM_inventory:cycle_count:initiate",
        "PERM_inventory:cycle_count:view",
        "PERM_inventory:cycle_count:complete",
        "PERM_inventory:on_hand:view",
        "PERM_inventory:on_hand:search",
        "PERM_inventory:receiving:create",
        "PERM_inventory:receiving:view",
        "PERM_inventory:receiving:complete",
        "PERM_inventory:issue:parts",
        "PERM_inventory:override:part-match",
        "PERM_inventory:purchase_order:create",
        "PERM_inventory:purchase_order:view",
        "PERM_inventory:purchase_order:approve",
        "PERM_inventory:purchase_order:receive",
        "PERM_inventory:asn:create",
        "PERM_inventory:asn:view",
        "PERM_inventory:goods_receipt:create",
        "PERM_inventory:goods_receipt:view",
        "PERM_inventory:goods_receipt:override",
        "PERM_inventory:allocations:reallocate",
        "PERM_inventory:shortages:resolve",
        "PERM_invoice:manage",
        "PERM_invoice:billing-rules",
        "PERM_invoice:finalize",
        "PERM_location:read",
        "PERM_location:write",
        "PERM_location:bay:read",
        "PERM_location:bay:manage",
        "PERM_location:mobile-unit:read",
        "PERM_location:mobile-unit:manage",
        "PERM_location:service-area:read",
        "PERM_location:service-area:manage",
        "PERM_location:travel-buffer-policy:read",
        "PERM_location:travel-buffer-policy:manage",
        "PERM_mcp:llm_api:view",
        "PERM_mcp:llm_api:create",
        "PERM_mcp:llm_api:update",
        "PERM_mcp:llm_api:delete",
        "PERM_mcp:system_prompt:view",
        "PERM_mcp:system_prompt:create",
        "PERM_mcp:system_prompt:update",
        "PERM_mcp:system_prompt:delete",
        "PERM_order:order:view",
        "PERM_order:order:create",
        "PERM_order:order:edit",
        "PERM_order:order:cancel",
        "PERM_order:line:view",
        "PERM_order:line:create",
        "PERM_order:line:edit",
        "PERM_order:line:delete",
        "PERM_order:line:enter_manual_price",
        "PERM_order:price_override:view",
        "PERM_order:price_override:apply",
        "PERM_order:price_override:approve",
        "PERM_order:price_override:reject",
        "PERM_people:employee:view",
        "PERM_people:employee:create",
        "PERM_people:employee:edit",
        "PERM_people:employee:deactivate",
        "PERM_people:role:view",
        "PERM_people:role:assign",
        "PERM_people:role:revoke",
        "PERM_people:skill:view",
        "PERM_people:skill:assign",
        "PERM_people:skill:edit",
        "PERM_pricing:price_book:view",
        "PERM_pricing:price_book:create",
        "PERM_pricing:price_book:edit",
        "PERM_pricing:price_book:delete",
        "PERM_pricing:normalization:view",
        "PERM_pricing:normalization:edit",
        "PERM_pricing:restrictions:view",
        "PERM_pricing:restrictions:edit",
        "PERM_pricing:rule:view",
        "PERM_pricing:rule:create",
        "PERM_pricing:rule:edit",
        "PERM_pricing:rule:delete",
        "PERM_security:role:view",
        "PERM_security:role:create",
        "PERM_security:role:edit",
        "PERM_security:role:delete",
        "PERM_security:role:assign",
        "PERM_security:permission:view",
        "PERM_security:permission:register",
        "PERM_security:user:view",
        "PERM_security:user:create",
        "PERM_security:user:edit",
        "PERM_security:user:delete",
        "PERM_shop:location:view",
        "PERM_shop:location:create",
        "PERM_shop:location:edit",
        "PERM_shop:location:deactivate",
        "PERM_shop:bay:view",
        "PERM_shop:bay:create",
        "PERM_shop:bay:edit",
        "PERM_shop:bay:assign",
        "PERM_shop:schedule:view",
        "PERM_shop:schedule:edit",
        "PERM_appointments:create",
        "PERM_appointments:view",
        "PERM_appointments:reschedule",
        "PERM_appointments:cancel",
        "PERM_tax:calculate",
        "PERM_tax:mode:view",
        "PERM_vehicle-fitment:hint:view",
        "PERM_vehicle-fitment:hint:create",
        "PERM_vehicle-fitment:hint:update",
        "PERM_vehicle-fitment:hint:delete",
        "PERM_vehicle-fitment:catalog:view",
        "PERM_vehicle-inventory:registry:view",
        "PERM_vehicle-inventory:registry:create",
        "PERM_vehicle-inventory:registry:update",
        "PERM_vehicle-inventory:registry:delete",
        "PERM_vehicle-inventory:preferences:manage",
        "PERM_vehicle-inventory:search:view",
        "PERM_workorder:workorder:view",
        "PERM_workorder:workorder:create",
        "PERM_workorder:workorder:edit",
        "PERM_workorder:workorder:delete",
        "PERM_workorder:workorder:start",
        "PERM_workorder:workorder:complete",
        "PERM_workorder:workorder:reopen_completed",
        "PERM_workorder:workorder:approve",
        "PERM_workorder:estimate:view",
        "PERM_workorder:estimate:create",
        "PERM_workorder:estimate:edit",
        "PERM_workorder:estimate:delete",
        "PERM_workorder:estimate:approve",
        "PERM_workorder:estimate:decline",
        "PERM_workorder:estimate:reopen",
        "PERM_workorder:estimate:calculate",
        "PERM_workorder:estimate_item:view",
        "PERM_workorder:estimate_item:add",
        "PERM_workorder:estimate_item:edit",
        "PERM_workorder:estimate_item:delete",
        "PERM_workorder:estimate_snapshot:create",
        "PERM_workorder:estimate_snapshot:view",
        "PERM_workorder:change_request:view",
        "PERM_workorder:change_request:create",
        "PERM_workorder:change_request:approve",
        "PERM_workorder:change_request:decline",
        "PERM_workorder:change_request:emergency_override",
        "PERM_workorder:approval_config:view",
        "PERM_workorder:approval_config:create",
        "PERM_workorder:approval_config:edit",
        "PERM_workorder:approval_config:delete",
        "PERM_workorder:invoice:view",
        "PERM_workorder:invoice:create",
        "PERM_workorder:labor:view",
        "PERM_workorder:labor:add",
        "PERM_workorder:parts:view",
        "PERM_workorder:parts:add",
        "PERM_workorder:wip:view",
        "PERM_workorder:wip:view_all_locations",
        "PERM_security:user_account_state:view",
        "PERM_security:user_account_state:manage",
        "PERM_security:audit:view",
        "PERM_security:audit:create",
        "PERM_security:authorization:decide",
        "PERM_security:token:issue_internal",
        "PERM_nlti:request:submit",
        "PERM_nlti:request:read",
        "PERM_nlti:audit:read",
        "PERM_mcp:document:ingest",
        "PERM_mcp:chat:stream",
        "PERM_mcp:chat:execute",
        "PERM_catalog:supplier_cost:read",
        "PERM_catalog:supplier_cost:write",
        "PERM_catalog:msrp:read",
        "PERM_catalog:msrp:write",
        "PERM_catalog:price_book:read",
        "PERM_catalog:price_book:write",
        "PERM_people:person:view",
        "PERM_people:person:create",
        "PERM_people:person:edit",
        "PERM_people:person:delete",
        "PERM_people:userLink:view",
        "PERM_people:userLink:write",
        "PERM_bulkImport:upload:execute",
        "PERM_bulkImport:status:read",

        // ── Batch-2: previously missing from gateway (bits 241–261) ─────────────
        "PERM_accounting:events:reprocess", // 241
        "PERM_accounting:export:request", // 242
        "PERM_accounting:export:view", // 243
        "PERM_crm:billing_rules:edit", // 244
        "PERM_pricing:base_price:create", // 245
        "PERM_inventory:ledger:view", // 246
        "PERM_inventory:location:admin", // 247
        "PERM_inventory:location:view", // 248
        "PERM_inventory:pick_list:create", // 249
        "PERM_inventory:pick_list:execute", // 250
        "PERM_inventory:pick_list:view", // 251
        "PERM_inventory:putaway:claim", // 252
        "PERM_inventory:putaway:execute", // 253
        "PERM_inventory:putaway:generate", // 254
        "PERM_inventory:putaway:view", // 255
        "PERM_inventory:return:view", // 256
        "PERM_inventory:return:write", // 257
        "PERM_inventory:shortage:resolve", // 258
        "PERM_inventory:shortage:view", // 259
        "PERM_inventory:stock_movement:create", // 260
        "PERM_workorder:parts:consume", // 261

        // ── Batch-3: dark permissions now in PermissionCode (bits 262–284) ───────
        "PERM_accounting:ap:approve", // 262
        "PERM_accounting:ap:reject", // 263
        "PERM_accounting:coa:deactivate", // 264
        "PERM_accounting:je:reverse", // 265
        "PERM_accounting:mapping:view", // 266
        "PERM_accounting:mapping:create", // 267
        "PERM_accounting:mapping:edit", // 268
        "PERM_accounting:mapping:deactivate", // 269
        "PERM_accounting:posting_rules:view", // 270
        "PERM_accounting:posting_rules:create", // 271
        "PERM_accounting:posting_rules:publish", // 272
        "PERM_accounting:posting_rules:archive", // 273
        "PERM_timekeeping:work_session:create", // 274
        "PERM_timekeeping:work_session:stop", // 275
        "PERM_timekeeping:work_session:break_start", // 276
        "PERM_timekeeping:work_session:break_stop", // 277
        "PERM_timekeeping:overlap_override", // 278
        "PERM_workorder:dashboard:view", // 279
        "PERM_workorder:estimate:submit", // 280
        "PERM_workorder:estimate:promote", // 281
        "PERM_workorder:workorder:assign-technician", // 282
        "PERM_workorder:workorder:generate_invoice", // 283
        "PERM_workorder:start", // 284

        // ── New batch (bits 285–317) ──────────────────────────────────────────
        "PERM_accounting:credit-memo:create", // 285
        "PERM_accounting:credit-memo:read", // 286
        "PERM_accounting:default-mapping:create", // 287
        "PERM_accounting:default-mapping:delete", // 288
        "PERM_accounting:default-mapping:edit", // 289
        "PERM_accounting:default-mapping:view", // 290
        "PERM_accounting:gl-mapping:create", // 291
        "PERM_accounting:gl-mapping:resolve", // 292
        "PERM_accounting:mapping-key:create", // 293
        "PERM_accounting:mapping-key:deactivate", // 294
        "PERM_accounting:mapping-key:edit", // 295
        "PERM_accounting:mapping-key:view", // 296
        "PERM_accounting:payment:apply", // 297
        "PERM_accounting:payment:reverse", // 298
        "PERM_accounting:posting-category:create", // 299
        "PERM_accounting:posting-category:deactivate", // 300
        "PERM_accounting:posting-category:edit", // 301
        "PERM_accounting:posting-category:view", // 302
        "PERM_accounting:report:export", // 303
        "PERM_accounting:time:export", // 304
        "PERM_crm:person:create", // 305
        "PERM_crm:person:read", // 306
        "PERM_crm:relationship:create", // 307
        "PERM_crm:relationship:delete", // 308
        "PERM_crm:relationship:read", // 309
        "PERM_crm:relationship:update", // 310
        "PERM_inventory:availability:read", // 311
        "PERM_people:availability:view", // 312
        "PERM_pricing:override:approve", // 313
        "PERM_pricing:restriction:manage", // 314
        "PERM_pricing:restriction:override", // 315
        "PERM_reporting:view:financial-statements", // 316
        "PERM_security:audit:export", // 317

        // ── New batch (bits 318–327) ──────────────────────────────────────────
        "PERM_people:timeAdjustment:approve", // 318
        "PERM_people:timeAdjustment:create", // 319
        "PERM_people:timeAdjustment:view", // 320
        "PERM_people:timeEntry:approve", // 321
        "PERM_people:timeEntry:reject", // 322
        "PERM_people:timeException:acknowledge", // 323
        "PERM_people:timeException:create", // 324
        "PERM_people:timeException:resolve", // 325
        "PERM_people:timeException:view", // 326
        "PERM_workorder:operationalContext:override", // 327

        // ── People timekeeping approval (bits 328–330) ────────────────────────
        "PERM_people:timekeeping:approve", // 328
        "PERM_people:timekeeping:reject", // 329
        "PERM_people:timekeeping:view", // 330

        // ── New batch (bits 331–337) ──────────────────────────────────────────
        "PERM_Promotion:Apply", // 331
        "PERM_Promotion:Manage", // 332
        "PERM_Promotion:RecordRedemption", // 333
        "PERM_Promotion:View", // 334
        "PERM_Promotion:ViewRedemption", // 335
        "PERM_TimeEntry:Approve", // 336
        "PERM_TimeEntry:Reject", // 337

        // ── New batch (bits 338–344) ──────────────────────────────────────────
        "PERM_crm:promotion_redemption:record", // 338
        "PERM_crm:promotion_redemption:view", // 339
        "PERM_pricing:promotion:apply", // 340
        "PERM_pricing:promotion:manage", // 341
        "PERM_pricing:promotion:view", // 342
        "PERM_workorder:timeEntry:approve", // 343
        "PERM_workorder:timeEntry:reject", // 344

        // ── New batch (bits 345–345) ──────────────────────────────────────────
        "PERM_workorder:labor:add_on_behalf", // 345

        // ── New batch (bits 346–346) ──────────────────────────────────────────
        "PERM_invoice:finalize:override", // 346

        // ── New batch (bits 347–348) ──────────────────────────────────────────
        "PERM_mcp:tool:manage", // 347
        "PERM_mcp:tool:view", // 348

        // ── New batch (bits 349–349) ──────────────────────────────────────────
        "PERM_inventory:location:sync", // 349

        // ── New batch (bits 350–350) ──────────────────────────────────────────
        "PERM_workorder:events:replay", // 350

        // ── People Contact (bits 351–359, ADR-0044 Phase 3 split #874) ────────
        "PERM_people-contact:person:view", // 351
        "PERM_people-contact:person:create", // 352
        "PERM_people-contact:person:edit", // 353
        "PERM_people-contact:person:delete", // 354
        "PERM_people-contact:role:view", // 355
        "PERM_people-contact:role:assign", // 356
        "PERM_people-contact:role:revoke", // 357
        "PERM_people-contact:userLink:view", // 358
        "PERM_people-contact:userLink:write", // 359

        // ── New batch (bits 360–361) ──────────────────────────────────────────
        "PERM_people:compliance:view", // 360
        "PERM_shop:technician:view", // 361

        // ── Warranty claims module (bits 362–378) ─────────────────────────────
        "PERM_warranty:provider:view", // 362
        "PERM_warranty:provider:manage", // 363
        "PERM_warranty:policy:view", // 364
        "PERM_warranty:policy:manage", // 365
        "PERM_warranty:registration:view", // 366
        "PERM_warranty:registration:manage", // 367
        "PERM_warranty:claim:view", // 368
        "PERM_warranty:claim:create", // 369
        "PERM_warranty:claim:submit", // 370
        "PERM_warranty:claim:decide", // 371
        "PERM_warranty:claim:settle", // 372
        "PERM_warranty:claim:cancel", // 373
        "PERM_warranty:claim:close", // 374
        "PERM_warranty:reimbursement:view", // 375
        "PERM_warranty:reimbursement:manage", // 376
        "PERM_warranty:part-return:view", // 377
        "PERM_warranty:part-return:manage", // 378

        // ── New batch (bits 379–381) ──────────────────────────────────────────
        "PERM_accounting:period:close", // 379
        "PERM_accounting:period:reopen", // 380
        "PERM_accounting:period:view", // 381

        // ── New batch (bits 382–383) ──────────────────────────────────────────
        "PERM_accounting:period:hard_lock", // 382
        "PERM_accounting:period:override", // 383

        // ── New batch (bits 384–385) ──────────────────────────────────────────
        "PERM_accounting:reconciliation:adjust",             // 384
        "PERM_accounting:reconciliation:view"               // 385
    };

    public static String authorityForBit(int bitIndex) {
        if (bitIndex < 0 || bitIndex >= AUTHORITY_BY_BIT.length) {
            return null;
        }
        return AUTHORITY_BY_BIT[bitIndex];
    }

    public static List<String> authoritiesFromBitSet(BitSet bits) {
        List<String> result = new ArrayList<>();
        for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
            String authority = authorityForBit(i);
            if (authority != null) {
                result.add(authority);
            }
        }
        return List.copyOf(result);
    }
}
