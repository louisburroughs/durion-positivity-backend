package com.positivity.catalog.internal.config;

import com.positivity.events.EventTypeRegistration;
import java.util.List;

/**
 * Registry of all event types emitted by the pos-catalog module.
 */
public final class CatalogEventTypes {

    private CatalogEventTypes() {
        // Utility class
    }

    /**
     * All event type registrations for the catalog module.
     */
    public static List<EventTypeRegistration> all() {
        return List.of(
                EventTypeRegistration.write(
                                "CATALOG_ITEM_CREATE", "Create a new catalog item (product, service, or non-inventory)")
                        .build(),
                EventTypeRegistration.write("CATALOG_ITEM_UPDATE", "Update an existing catalog item")
                        .build(),
                EventTypeRegistration.write("CATALOG_ITEM_DELETE", "Delete an existing catalog item")
                        .build(),
                EventTypeRegistration.write("CATALOG_CATALOG_CREATE", "Create a new catalog")
                        .build(),
                EventTypeRegistration.write("CATALOG_CATALOG_UPDATE", "Update an existing catalog")
                        .build(),
                EventTypeRegistration.write("CATALOG_CATALOG_DELETE", "Delete an existing catalog")
                        .build(),
                EventTypeRegistration.fastRead(
                                "CATALOG_PRODUCT_LIFECYCLE_GET", "Get product lifecycle and replacement suggestions")
                        .build(),
                EventTypeRegistration.fastRead(
                                "CATALOG_PRODUCT_CODE_LOOKUP", "Resolve a product by exact EAN or UPC product code")
                        .build(),
                // Replica seeding / repair (ADR-0044 §4, #1309). An approval-grade budget rather
                // than a write: one call queues up to a thousand facts, so a write threshold would
                // alert on every healthy replay.
                EventTypeRegistration.approval(
                                "CATALOG_PRODUCT_FACT_REPLAY", "Re-emit product facts for event-fed replica consumers")
                        .build(),
                // Replica seeding / repair for service facts (#1306), same approval-grade budget as
                // CATALOG_PRODUCT_FACT_REPLAY: one call queues up to a thousand facts, so a write
                // threshold would alert on every healthy replay.
                EventTypeRegistration.approval(
                                "CATALOG_SERVICE_FACT_REPLAY", "Re-emit service facts for event-fed replica consumers")
                        .build(),
                // Supplier price entries applied from PRICAT imports (ADR-0053, #1308)
                EventTypeRegistration.fastRead(
                                "CATALOG_SUPPLIER_PRICE_LATEST",
                                "Get the applicable vendor price for a product in one market")
                        .build(),
                EventTypeRegistration.search(
                                "CATALOG_SUPPLIER_PRICE_HISTORY", "List vendor price history for a product")
                        .build(),
                EventTypeRegistration.fastRead(
                                "CATALOG_SUPPLIER_PRICE_IMPORT_GAPS",
                                "List vendor price imports this module could not confirm complete")
                        .build(),
                // Replica seeding / repair for vendor article codes (CAP-320 #1347), same
                // approval-grade budget as CATALOG_PRODUCT_FACT_REPLAY and for the same reason.
                EventTypeRegistration.approval(
                                "CATALOG_SUPPLIER_ARTICLE_CODE_REPLAY",
                                "Re-emit vendor article code facts for event-fed replica consumers")
                        .build(),
                // MKCAT tread-design enrichment reads (CAP-324 #1352)
                EventTypeRegistration.fastRead(
                                "CATALOG_TREAD_DESIGN_FOR_PRODUCT", "Get vendor tread-design enrichment for a product")
                        .build(),
                EventTypeRegistration.search(
                                "CATALOG_TREAD_DESIGN_UNMATCHED_LIST", "List tread designs awaiting enrichment review")
                        .build(),
                // Enrichment review (#1645). The candidate list is a fastRead — one design's scored
                // products, opened from a worklist row. The resolve action is approval-grade: it is
                // a person overruling the matcher, and it changes what every shop sees on a product.
                EventTypeRegistration.fastRead(
                                "CATALOG_TREAD_DESIGN_CANDIDATES_LIST",
                                "List the products the matcher scored against a tread design")
                        .build(),
                EventTypeRegistration.approval(
                                "CATALOG_TREAD_DESIGN_RESOLVE",
                                "Attach, reject or defer a tread design awaiting review")
                        .build(),
                EventTypeRegistration.write(
                                "CATALOG_PRODUCT_LIFECYCLE_UPDATE", "Set product lifecycle state with effective date")
                        .build(),
                EventTypeRegistration.write("CATALOG_PRODUCT_CREATED", "Create product master record")
                        .build(),
                EventTypeRegistration.write("CATALOG_PRODUCT_UPDATED", "Update product master record")
                        .build(),
                EventTypeRegistration.write("CATALOG_PRODUCT_STATUS_CHANGED", "Change product operational status")
                        .build(),
                EventTypeRegistration.write("CATALOG_PRODUCT_LIFECYCLE_CHANGED", "Set product lifecycle state")
                        .build(),
                EventTypeRegistration.write(
                                "CATALOG_PRODUCT_REPLACEMENT_ADD",
                                "Add replacement recommendation for a discontinued product")
                        .build(),
                EventTypeRegistration.write(
                                "CATALOG_PRODUCT_TRACKING_LEVEL_UPDATE",
                                "Set product stock tracking level (NONE, LOT, SERIAL)")
                        .build(),
                EventTypeRegistration.write("CATALOG_PRODUCT_UOM_CREATE", "Add per-product UOM conversion")
                        .build(),
                EventTypeRegistration.write("CATALOG_PRODUCT_UOM_UPDATE", "Update per-product UOM conversion")
                        .build(),
                EventTypeRegistration.write("CATALOG_PRODUCT_UOM_DELETE", "Delete per-product UOM conversion")
                        .build(),
                EventTypeRegistration.write("CATALOG_SUBSTITUTION_GROUP_CREATE", "Create substitution group")
                        .build(),
                EventTypeRegistration.write("CATALOG_SUBSTITUTION_GROUP_DELETE", "Delete substitution group")
                        .build(),
                EventTypeRegistration.write(
                                "CATALOG_SUBSTITUTION_GROUP_MEMBER_ADD", "Add product to substitution group")
                        .build(),
                EventTypeRegistration.write(
                                "CATALOG_SUBSTITUTION_GROUP_MEMBER_REMOVE", "Remove product from substitution group")
                        .build(),
                EventTypeRegistration.write("CATALOG_UOM_CONVERSION_CREATE", "Create UOM conversion")
                        .build(),
                EventTypeRegistration.write("CATALOG_UOM_CONVERSION_UPDATE", "Update UOM conversion factor")
                        .build(),
                EventTypeRegistration.write("CATALOG_UOM_CONVERSION_DEACTIVATE", "Deactivate UOM conversion")
                        .build(),
                EventTypeRegistration.write("CATALOG_SUPPLIER_COST_CREATE", "Create supplier item cost structure")
                        .build(),
                EventTypeRegistration.write("CATALOG_SUPPLIER_COST_UPDATE", "Update supplier item cost structure")
                        .build(),
                EventTypeRegistration.write("CATALOG_SUPPLIER_COST_DELETE", "Delete supplier item cost structure")
                        .build(),
                EventTypeRegistration.search(
                                "CATALOG_SUPPLIER_COST_LIST", "List supplier item cost structures by filter")
                        .build(),
                EventTypeRegistration.write("CATALOG_ITEM_COST_STANDARD_UPDATE", "Manually update item standard cost")
                        .build(),
                EventTypeRegistration.write(
                                "CATALOG_GUARDRAIL_POLICY_UPSERT",
                                "Create or update location guardrail policy for price overrides")
                        .build(),
                EventTypeRegistration.write(
                                "CATALOG_LOCATION_OVERRIDE_CREATE",
                                "Create location-specific price override with guardrail checks")
                        .build(),
                EventTypeRegistration.approval(
                                "CATALOG_LOCATION_OVERRIDE_APPROVE",
                                "Approve pending location price override and activate effective price")
                        .build(),
                EventTypeRegistration.approval(
                                "CATALOG_LOCATION_OVERRIDE_REJECT",
                                "Reject pending location price override with reason metadata")
                        .build(),
                EventTypeRegistration.write("CATALOG_MSRP_CREATE", "Create MSRP record")
                        .build(),
                EventTypeRegistration.write("CATALOG_MSRP_UPDATE", "Update MSRP record")
                        .build(),
                EventTypeRegistration.write("CATALOG_PRICE_BOOK_CREATE", "Create price book")
                        .build(),
                EventTypeRegistration.write("CATALOG_PRICE_BOOK_UPDATE", "Update price book")
                        .build(),
                EventTypeRegistration.write("CATALOG_PRICE_BOOK_RULE_CREATE", "Create price book rule")
                        .build(),
                EventTypeRegistration.write("CATALOG_PRICE_BOOK_RULE_UPDATE", "Update price book rule")
                        .build(),
                EventTypeRegistration.write("CATALOG_PRICE_BOOK_RULE_DEACTIVATE", "Deactivate price book rule")
                        .build(),
                EventTypeRegistration.fastRead("CATALOG_PRICE_BOOK_RESOLVE_PRICE", "Resolve effective price")
                        .build(),
                EventTypeRegistration.write("CATALOG_BULK_INGEST", "Bulk ingest catalog products")
                        .build(),
                // Labor standards — vehicle-keyed estimated service times (#1569)
                EventTypeRegistration.write(
                                "CATALOG_LABOR_STANDARD_CREATE", "Author a DURION-source labor standard for a service")
                        .build(),
                EventTypeRegistration.write(
                                "CATALOG_LABOR_STANDARD_SUPERSEDE",
                                "Supersede a labor standard with a corrected replacement row")
                        .build(),
                EventTypeRegistration.search(
                                "CATALOG_LABOR_STANDARD_LIST", "List a service's labor standards with provenance")
                        .build(),
                // Labor-guide ingestion + resolution (#1569 Phase 1). The import gets an
                // approval-grade budget for the same reason the fact replays do: one call
                // legitimately writes thousands of rows, so a write threshold would alert on
                // every healthy import.
                EventTypeRegistration.approval(
                                "CATALOG_LABOR_GUIDE_IMPORT", "Import a labor-guide feed revision from a STORE source")
                        .build(),
                EventTypeRegistration.fastRead(
                                "CATALOG_LABOR_GUIDE_IMPORT_GAPS",
                                "List labor-guide imports this module could not confirm complete")
                        .build(),
                EventTypeRegistration.search(
                                "CATALOG_LABOR_GUIDE_UNMAPPED_LIST",
                                "List vendor operation codes awaiting curation into the xref")
                        .build(),
                EventTypeRegistration.fastRead(
                                "CATALOG_LABOR_TIME_RESOLVE",
                                "Resolve the applicable labor time for a service operation and vehicle")
                        .build());
    }
}
