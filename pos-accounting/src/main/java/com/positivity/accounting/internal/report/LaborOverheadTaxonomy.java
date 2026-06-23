package com.positivity.accounting.internal.report;

import com.positivity.accounting.internal.enums.CostType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical CAP-316 "Labor &amp; Overhead Costs" cost-line taxonomy.
 *
 * <p>Codes, labels, hierarchy, Fixed/Variable type, and definitions are taken verbatim from the
 * CAP-316 spec §3 (source workbook "Master Form" + "Definitions" tabs). The order and numbering are
 * <b>contractual</b> — the report renders lines in exactly this sequence.
 *
 * <p>Leaf lines ({@link LineDef#isSubtotal()} {@code == false}) are resolved to GL accounts via the
 * persisted {@code StatementLineMapping} ({@code StatementType.LABOR_OVERHEAD}). Subtotal lines are
 * computed column-wise from their {@link LineDef#childCodes()} and are never mapped directly.
 *
 * <p>The {@code *} annotation on a source type ("Fixed if volume change is minimal") maps to
 * {@link CostType#FIXED_IF_LOW_VOLUME}; otherwise the base classification is used. Lines with no
 * source classification carry a {@code null} cost type.
 */
public final class LaborOverheadTaxonomy {

    /** Synthetic codes for the section/grand total rows (the source form has no numeric code for these). */
    public static final String TOTAL_LABOR = "TOTAL_LABOR";

    public static final String TOTAL_OVERHEAD = "TOTAL_OVERHEAD";

    public static final String TOTAL_LABOR_OVERHEAD = "TOTAL_LABOR_OVERHEAD";

    /**
     * A single cost line in the canonical taxonomy.
     *
     * @param code unique line code (e.g. {@code "1.1.1"} or a synthetic total code)
     * @param label display label
     * @param parentCode parent line code, or {@code null} for top-level / total rows
     * @param level hierarchy depth (totals = 1; {@code N.M} = 2; {@code N.M.K} = 3)
     * @param costType Fixed/Variable classification, or {@code null} when the source shows none
     * @param isSubtotal {@code true} for computed roll-up rows; {@code false} for GL-mapped leaves
     * @param usdOnly {@code true} for line 2.11.4 (reported in USD even on local-currency plants)
     * @param definition include/exclude guidance from the Definitions tab
     * @param childCodes ordered child codes for subtotal rows; empty for leaves
     */
    public record LineDef(
            String code,
            String label,
            String parentCode,
            int level,
            CostType costType,
            boolean isSubtotal,
            boolean usdOnly,
            String definition,
            List<String> childCodes) {}

    private static final List<LineDef> LINES = buildLines();

    private static final Map<String, LineDef> BY_CODE = indexByCode(LINES);

    private LaborOverheadTaxonomy() {}

    /** The full ordered taxonomy in canonical (contractual) order. */
    public static List<LineDef> lines() {
        return LINES;
    }

    /** Leaf (GL-mapped) line codes, in canonical order. */
    public static List<String> leafCodes() {
        List<String> leaves = new ArrayList<>();
        for (LineDef line : LINES) {
            if (!line.isSubtotal()) {
                leaves.add(line.code());
            }
        }
        return leaves;
    }

    public static LineDef byCode(String code) {
        return BY_CODE.get(code);
    }

    private static Map<String, LineDef> indexByCode(List<LineDef> lines) {
        Map<String, LineDef> map = new LinkedHashMap<>();
        for (LineDef line : lines) {
            map.put(line.code(), line);
        }
        return map;
    }

    private static List<LineDef> buildLines() {
        List<LineDef> lines = new ArrayList<>();

        // ----------------------------------------------------------------- §1 Labor
        lines.add(subtotal(
                "1.1",
                "Wages and Bonuses (including shop manager)",
                null,
                2,
                "Sum of 1.1.1–1.1.2.",
                List.of("1.1.1", "1.1.2")));
        lines.add(leaf(
                "1.1.1",
                "Hourly wages and bonuses",
                "1.1",
                3,
                CostType.VARIABLE,
                false,
                "Incl: wages/bonuses for hourly retread-plant workers (production, maintenance, janitor, plant clerk),"
                        + " matching MRT Weekly Production Report hours. Excl: warehousing/distribution/sales/corporate"
                        + " office wages; production outside the MRT process."));
        lines.add(leaf(
                "1.1.2",
                "Management salaries",
                "1.1",
                3,
                CostType.FIXED_IF_LOW_VOLUME,
                false,
                "Incl: salaries/bonuses for salaried retread-plant personnel matching MRT Weekly Production Report"
                        + " hours."));
        lines.add(leaf(
                "1.2",
                "Misc Labor (contract and temp production employees)",
                null,
                2,
                CostType.VARIABLE,
                false,
                "Incl: wages for temporary/contract workers matching MRT Weekly Production Report hours."));
        lines.add(subtotal(
                "1.3",
                "Taxes and Benefits",
                null,
                2,
                "Relating to the wages in §1.1.",
                List.of("1.3.1", "1.3.2", "1.3.3", "1.3.4", "1.3.5", "1.3.6")));
        lines.add(leaf("1.3.1", "FICA", "1.3", 3, CostType.VARIABLE, false, "Payroll tax on §1.1 wages."));
        lines.add(leaf(
                "1.3.2",
                "Fed Unemployment",
                "1.3",
                3,
                CostType.VARIABLE,
                false,
                "Federal unemployment tax on §1.1 wages."));
        lines.add(leaf(
                "1.3.3",
                "State Unemployment",
                "1.3",
                3,
                CostType.VARIABLE,
                false,
                "State unemployment tax on §1.1 wages."));
        lines.add(leaf(
                "1.3.4",
                "Medical/dental insurance, life, health, disability",
                "1.3",
                3,
                CostType.VARIABLE,
                false,
                "Employee benefit costs related to §1.1."));
        lines.add(leaf(
                "1.3.5",
                "Retirement plan contributions",
                "1.3",
                3,
                CostType.VARIABLE,
                false,
                "Employer retirement contributions related to §1.1."));
        lines.add(leaf(
                "1.3.6",
                "Employee Insurance – workers' comp",
                "1.3",
                3,
                CostType.VARIABLE,
                false,
                "Workers' compensation insurance for plant employees."));
        lines.add(leaf(
                "1.5",
                "Uniforms rental / laundry",
                null,
                2,
                CostType.FIXED_IF_LOW_VOLUME,
                false,
                "Incl: uniforms or shirt/trouser program, if provided."));
        lines.add(total(
                TOTAL_LABOR,
                "Total Labor",
                "Section subtotal: sum of 1.1, 1.2, 1.3, 1.5.",
                List.of("1.1", "1.2", "1.3", "1.5")));

        // -------------------------------------------------------------- §2 Overhead
        lines.add(subtotal("2.1", "Building", null, 2, "Sum of 2.1.1–2.1.3.", List.of("2.1.1", "2.1.2", "2.1.3")));
        lines.add(leaf(
                "2.1.1",
                "Depreciation",
                "2.1",
                3,
                CostType.FIXED,
                false,
                "Incl: \"bricks & mortar\", only if owned, 20-yr straight line. Excl: if renting (use 2.1.3);"
                        + " building-financing interest; land depreciation."));
        lines.add(leaf(
                "2.1.2",
                "Maintenance",
                "2.1",
                3,
                CostType.FIXED,
                false,
                "Incl: building maintenance, garbage removal fees."));
        lines.add(leaf(
                "2.1.3",
                "Rent",
                "2.1",
                3,
                CostType.FIXED,
                false,
                "Incl: building rental (only the retread-plant % of footage); leasehold improvements if owned."
                        + " Excl: mortgage payments."));
        lines.add(leaf(
                "2.2",
                "Training Costs",
                null,
                2,
                CostType.FIXED_IF_LOW_VOLUME,
                false,
                "Incl: outside-trainer expense. Excl: trainer/trainee labor (use 1.1.1)."));
        lines.add(leaf(
                "2.3",
                "Employment advertising / recruiting costs",
                null,
                2,
                CostType.FIXED,
                false,
                "Recruiting/advertising for plant staff."));
        lines.add(subtotal(
                "2.4",
                "Vehicle expenses (rubber dust trailer, shop truck)",
                null,
                2,
                "Incl: vehicles for retread-plant use only; rubber-dust trailer fees. Excl: sales/distribution/"
                        + "warehousing vehicles; vehicle insurance (use 2.7.3); forklifts (use 2.11.5).",
                List.of("2.4.1", "2.4.2", "2.4.3", "2.4.4", "2.4.5")));
        lines.add(leaf("2.4.1", "Gas & Oil", "2.4", 3, null, false, "Fuel/oil for plant vehicles."));
        lines.add(leaf("2.4.2", "Maintenance", "2.4", 3, null, false, "Maintenance for plant vehicles."));
        lines.add(leaf("2.4.3", "Taxes", "2.4", 3, null, false, "Taxes on plant vehicles."));
        lines.add(leaf(
                "2.4.4", "Depreciation", "2.4", 3, null, false, "Depreciation of plant vehicles (standard methods)."));
        lines.add(leaf("2.4.5", "Rent", "2.4", 3, null, false, "Rental of plant vehicles."));
        lines.add(leaf(
                "2.5",
                "Telephone",
                null,
                2,
                CostType.FIXED,
                false,
                "Incl: voice/modem/data lines for plant; cell phones for plant personnel."));
        lines.add(leaf(
                "2.6",
                "Travel and Entertainment",
                null,
                2,
                CostType.FIXED,
                false,
                "Incl: travel for plant manager/employees (e.g. PMPC). Excl: owner/corporate travel;"
                        + " promotional/entertainment (sales cost)."));
        lines.add(subtotal(
                "2.7",
                "Insurance",
                null,
                2,
                "Incl: fire/theft/liability for the retread plant; plant-vehicle insurance (2.7.3)."
                        + " Excl: warehouse/sales insurance.",
                List.of("2.7.1", "2.7.2", "2.7.3")));
        lines.add(leaf("2.7.1", "Fire", "2.7", 3, null, false, "Fire insurance for the plant."));
        lines.add(leaf("2.7.2", "Theft", "2.7", 3, null, false, "Theft insurance for the plant."));
        lines.add(leaf(
                "2.7.3",
                "Liability",
                "2.7",
                3,
                null,
                false,
                "Liability insurance for the plant (incl. plant vehicles)."));
        lines.add(leaf(
                "2.8",
                "Property Taxes",
                null,
                2,
                CostType.FIXED,
                false,
                "Incl: property taxes for dealer-owned plant. Excl: if building rented; sales/income taxes."));
        lines.add(subtotal(
                "2.9", "Supplies", null, 2, "Sum of 2.9.1–2.9.4.", List.of("2.9.1", "2.9.2", "2.9.3", "2.9.4")));
        lines.add(leaf(
                "2.9.1",
                "Shop Consumables (rasps, grinding wheels, brushes)",
                "2.9",
                3,
                CostType.VARIABLE,
                false,
                "Incl: buffing blades/brushes, grinding wheels. Excl: materials assembled to the tire (tread rubber,"
                        + " cushion gum, rope rubber, patches, cement, paint)."));
        lines.add(leaf(
                "2.9.2",
                "Curing Consumables (envelopes, lube, wicks, poly)",
                "2.9",
                3,
                CostType.VARIABLE,
                false,
                "Incl: curing envelopes, wicks, envelope lube, poly."));
        lines.add(leaf(
                "2.9.3",
                "Miscellaneous",
                "2.9",
                3,
                CostType.FIXED,
                false,
                "Incl: janitorial supplies, treatment chemicals, etc."));
        lines.add(leaf(
                "2.9.4",
                "Office",
                "2.9",
                3,
                CostType.FIXED_IF_LOW_VOLUME,
                false,
                "Incl: paper, pens, labels, office supplies."));
        lines.add(leaf(
                "2.10",
                "Utilities – gas, electric, water",
                null,
                2,
                CostType.VARIABLE,
                false,
                "Incl: gas/electric/water for the plant (may be an allocated % if shared). Excl: utilities for"
                        + " other operations in the building."));
        lines.add(subtotal(
                "2.11",
                "Equipment expense",
                null,
                2,
                "Sum of 2.11.1–2.11.6.",
                List.of("2.11.1", "2.11.2", "2.11.3", "2.11.4", "2.11.5", "2.11.6")));
        lines.add(leaf(
                "2.11.1",
                "Maintenance",
                "2.11",
                3,
                CostType.FIXED_IF_LOW_VOLUME,
                false,
                "Incl: equipment repairs/spare parts, computer maintenance, custom-mold cleaning. Excl: items under"
                        + " MRT warranty."));
        lines.add(leaf(
                "2.11.2", "Rental", "2.11", 3, CostType.FIXED, false, "Incl: rental fees for air compressors, etc."));
        lines.add(leaf(
                "2.11.3",
                "Small tools and equipment",
                "2.11",
                3,
                CostType.FIXED,
                false,
                "Small tools/equipment purchases."));
        lines.add(leaf(
                "2.11.4",
                "Depreciation – MRT process equipment ($US only)",
                "2.11",
                3,
                CostType.FIXED,
                true,
                "Incl: MRT process equipment & installation at start-up/expansion (regardless of ownership); MRT mods"
                        + " > $5,000; 10-yr straight line (MRT provides value). Excl: non-MRT-process equipment."
                        + " Reported in USD even on local-currency plants."));
        lines.add(leaf(
                "2.11.5",
                "Depreciation – Other shop equipment (boiler, cyclone, air compressors, capital cost of conversion)",
                "2.11",
                3,
                CostType.FIXED,
                false,
                "Incl: dealer-installed process support equipment, forklifts, capital conversion costs; 10-yr straight"
                        + " line (forklifts/computers use std methods). Excl: MRT-provided gas hot-water unit (in"
                        + " 2.11.4); equipment sales taxes; equipment financing interest."));
        lines.add(leaf(
                "2.11.6",
                "MRTI equipment leases or rent",
                "2.11",
                3,
                CostType.FIXED,
                false,
                "Incl: MRT software license fees (CIA, BIB TREAD); custom-mold rental. Excl: MRT process equipment"
                        + " rented from MRT/3rd party (in 2.11.4)."));
        lines.add(leaf(
                "2.12",
                "Administration fees",
                null,
                2,
                CostType.FIXED,
                false,
                "Incl: allocation for corporate accounting/payroll/personnel support (consistent monthly, not"
                        + " %-of-sales). Excl: corporate costs that would persist if plant closed (senior mgmt"
                        + " salaries, sales commissions)."));
        lines.add(leaf(
                "2.13",
                "Inventory charge",
                null,
                2,
                CostType.VARIABLE,
                false,
                "Interest charge/credit for money funding inventory of materials & consumables. Expense = positive;"
                        + " interest income = negative. Excl: casing/stock-casing inventory charges; equipment"
                        + " financing interest."));
        lines.add(leaf(
                "2.14",
                "Income from rubber dust sales",
                null,
                2,
                CostType.VARIABLE,
                false,
                "Income from rubber-dust sales (enter as negative); or removal cost if not sold (positive). Excl:"
                        + " rubber-dust trailer rentals/fees (use 2.4)."));
        lines.add(
                subtotal("2.15", "Production Quality", null, 2, "Sum of 2.15.1–2.15.2.", List.of("2.15.1", "2.15.2")));
        lines.add(leaf(
                "2.15.1",
                "Casings scrapped in production (e.g. torn belts by buffer)",
                "2.15",
                3,
                CostType.VARIABLE,
                false,
                "Incl: cost of casings damaged by the plant during production (casing cost + disposal). Excl: RAR"
                        + " scrap disposal fees."));
        lines.add(leaf(
                "2.15.2",
                "Adjustments (customer returns, user price plus transportation both ways)",
                "2.15",
                3,
                CostType.VARIABLE,
                false,
                "Incl: materials/workmanship adjustment costs (fees, transport), shown in the month paid (not"
                        + " accrual), matching adjustments reported to MRT Quality. Excl: commercial concession"
                        + " adjustments; RAR disposal fees."));
        lines.add(total(
                TOTAL_OVERHEAD,
                "Total Overhead",
                "Section subtotal: sum of 2.1–2.15.",
                List.of(
                        "2.1", "2.2", "2.3", "2.4", "2.5", "2.6", "2.7", "2.8", "2.9", "2.10", "2.11", "2.12", "2.13",
                        "2.14", "2.15")));
        lines.add(total(
                TOTAL_LABOR_OVERHEAD,
                "Total Labor & Overhead",
                "Grand total: Total Labor + Total Overhead.",
                List.of(TOTAL_LABOR, TOTAL_OVERHEAD)));

        return List.copyOf(lines);
    }

    private static LineDef leaf(
            String code, String label, String parent, int level, CostType type, boolean usdOnly, String definition) {
        return new LineDef(code, label, parent, level, type, false, usdOnly, definition, List.of());
    }

    private static LineDef subtotal(
            String code, String label, String parent, int level, String definition, List<String> children) {
        return new LineDef(code, label, parent, level, null, true, false, definition, children);
    }

    private static LineDef total(String code, String label, String definition, List<String> children) {
        return new LineDef(code, label, null, 1, null, true, false, definition, children);
    }
}
